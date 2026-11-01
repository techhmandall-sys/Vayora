"""Read-only audit statistics over the existing event log. Writes nothing to the database.

Per-seed values for the primary metric and the late-cancellation share (with and without
driver withdrawals) for the ladder (...003) and robustness (...004) runs, then for each
pre-specified contrast: paired mean difference, t interval, percentile bootstrap interval,
Wilcoxon signed-rank p, Holm-adjusted p across the family, d_z, and the minimum detectable
effect at 80% power. Output: /audit/audit_stats.json
"""
import json
import os

import numpy as np
import psycopg
from scipy import stats

LADDER = "7a1c0de0-0000-4000-8000-000000000003"
ROBUST = "7a1c0de0-0000-4000-8000-000000000004"

PER_SEED = """
SELECT variant, seed,
  100.0 * COUNT(*) FILTER (WHERE event_type = 'trip_completed')
        / NULLIF(COUNT(*) FILTER (WHERE event_type = 'search_issued'), 0) AS per_100,
  COUNT(*) FILTER (WHERE event_type = 'search_issued') AS searches,
  COUNT(*) FILTER (WHERE event_type = 'trip_completed') AS completed,
  COUNT(*) FILTER (WHERE event_type = 'booking_cancelled') AS cancels_all,
  COUNT(*) FILTER (WHERE event_type = 'booking_cancelled' AND payload->>'cancelled_by' = 'passenger') AS cancels_pax,
  COUNT(*) FILTER (WHERE event_type = 'booking_cancelled'
                   AND CAST(payload->>'departure_time' AS timestamptz) - sim_time < INTERVAL '24 hours') AS late_all,
  COUNT(*) FILTER (WHERE event_type = 'booking_cancelled' AND payload->>'cancelled_by' = 'passenger'
                   AND CAST(payload->>'departure_time' AS timestamptz) - sim_time < INTERVAL '24 hours') AS late_pax
FROM events WHERE run_id = %s GROUP BY variant, seed ORDER BY variant, seed
"""

# Seats actually travelled: completed trips weighted by the seats of their booking.
SEATS = """
WITH req AS (
  SELECT variant, seed, (payload->>'booking_id')::int AS b, (payload->>'seats')::int AS seats
  FROM events WHERE run_id = %s AND event_type = 'booking_requested'),
done AS (
  SELECT variant, seed, (payload->>'booking_id')::int AS b
  FROM events WHERE run_id = %s AND event_type = 'trip_completed')
SELECT d.variant, d.seed, SUM(r.seats) AS seats_completed
FROM done d JOIN req r USING (variant, seed, b) GROUP BY d.variant, d.seed
"""


def fetch(conn, run):
    data = {}
    with conn.cursor() as cur:
        cur.execute(PER_SEED, (run,))
        for variant, seed, per_100, searches, completed, ca, cp, la, lp in cur.fetchall():
            data.setdefault(variant, {})[seed] = {
                "per_100": float(per_100), "searches": searches, "completed": completed,
                "late_share_all": la / ca if ca else None,
                "late_share_passenger": lp / cp if cp else None,
                "driver_share_of_cancels": (ca - cp) / ca if ca else None,
            }
        cur.execute(SEATS, (run, run))
        for variant, seed, seats in cur.fetchall():
            row = data[variant][seed]
            row["seats_per_100"] = 100.0 * float(seats) / row["searches"]
    return data


def contrast(data, baseline, variant, measure, rng):
    seeds = sorted(set(data[baseline]) & set(data[variant]))
    d = np.array([data[variant][s][measure] - data[baseline][s][measure] for s in seeds])
    n = len(d)
    mean, sd = d.mean(), d.std(ddof=1)
    half = stats.t.ppf(0.975, n - 1) * sd / np.sqrt(n)
    boots = rng.choice(d, size=(20000, n), replace=True).mean(axis=1)
    p = float(stats.wilcoxon(d).pvalue) if np.any(d) else 1.0
    # Smallest true mean difference a paired t test at alpha 0.05 detects with 80% power.
    mde = (stats.t.ppf(0.975, n - 1) + stats.t.ppf(0.80, n - 1)) * sd / np.sqrt(n)
    base_mean = np.mean([data[baseline][s][measure] for s in seeds])
    return {
        "baseline": baseline, "variant": variant, "measure": measure, "seeds": n,
        "baseline_mean": float(base_mean), "variant_mean": float(base_mean + mean),
        "mean_difference": float(mean), "relative_difference_pct": float(100 * mean / base_mean),
        "sd_difference": float(sd), "t_ci": [float(mean - half), float(mean + half)],
        "bootstrap_ci": [float(np.percentile(boots, 2.5)), float(np.percentile(boots, 97.5))],
        "wilcoxon_p": p, "d_z": float(mean / sd) if sd else None, "mde_80": float(mde),
        "seeds_up": int((d > 0).sum()), "seeds_down": int((d < 0).sum()),
    }


def holm(results):
    order = sorted(range(len(results)), key=lambda i: results[i]["wilcoxon_p"])
    m, running = len(results), 0.0
    for rank, i in enumerate(order):
        running = max(running, min(1.0, (m - rank) * results[i]["wilcoxon_p"]))
        results[i]["holm_p"] = running


def mean_of(data, variant, measure):
    vals = [v[measure] for v in data[variant].values() if v[measure] is not None]
    return float(np.mean(vals)), float(np.std(vals, ddof=1))


def main():
    rng = np.random.default_rng(20261029)
    with psycopg.connect(os.environ["VAYORA_DB_URL"]) as conn:
        ladder, robust = fetch(conn, LADDER), fetch(conn, ROBUST)

    family = [(ladder, "B0", v) for v in ("B3", "B4", "B5", "ORACLE")]
    family += [(ladder, "B3", v) for v in ("B4", "ORACLE")]
    family += [(robust, "B3", v) for v in ("B0", "B4", "B3-KM", "B4-KM", "ASK-ONLY", "B5", "B5-RANDOM")]
    primary = []
    for data, base, var in family:
        r = contrast(data, base, var, "per_100", rng)
        r["run"] = LADDER if data is ladder else ROBUST
        primary.append(r)
    holm(primary)

    seats = [contrast(ladder, "B0", v, "seats_per_100", rng) for v in ("B3", "B4")]
    seats.append(contrast(ladder, "B3", "B4", "seats_per_100", rng))
    late = []
    for measure in ("late_share_all", "late_share_passenger"):
        for base, var in (("B0", "B4"), ("B3", "B4"), ("B3", "ORACLE")):
            late.append(contrast(ladder, base, var, measure, rng))

    levels = {v: {m: mean_of(ladder, v, m) for m in
                  ("per_100", "seats_per_100", "late_share_all", "late_share_passenger", "driver_share_of_cancels")}
              for v in ladder}
    out = {"ladder_run": LADDER, "robustness_run": ROBUST, "bootstrap_resamples": 20000,
           "rng_seed": 20261029, "primary_contrasts": primary, "seat_weighted": seats,
           "late_share": late, "levels": levels}
    with open("/audit/audit_stats.json", "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    for r in primary:
        print(f"{r['variant']:>10} vs {r['baseline']:<3} {r['mean_difference']:+.3f} "
              f"t[{r['t_ci'][0]:+.3f},{r['t_ci'][1]:+.3f}] boot[{r['bootstrap_ci'][0]:+.3f},{r['bootstrap_ci'][1]:+.3f}] "
              f"p={r['wilcoxon_p']:.2g} holm={r['holm_p']:.2g} dz={r['d_z']:+.2f} mde={r['mde_80']:.3f} "
              f"rel={r['relative_difference_pct']:+.2f}%")


if __name__ == "__main__":
    main()
