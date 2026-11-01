"""Read-only: seat-weighted completion and mean completed party size, robustness run, vs B3."""
import json, os
import numpy as np, psycopg
import importlib.util
spec = importlib.util.spec_from_file_location("a", "/audit/audit_stats.py"); a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
rng = np.random.default_rng(20261029)
with psycopg.connect(os.environ["VAYORA_DB_URL"]) as conn:
    robust = a.fetch(conn, a.ROBUST)
    with conn.cursor() as cur:
        cur.execute("""
WITH req AS (SELECT variant, seed, (payload->>'booking_id')::int b, (payload->>'seats')::int seats
             FROM events WHERE run_id=%s AND event_type='booking_requested'),
done AS (SELECT variant, seed, (payload->>'booking_id')::int b FROM events WHERE run_id=%s AND event_type='trip_completed')
SELECT d.variant, AVG(r.seats)::float FROM done d JOIN req r USING (variant, seed, b) GROUP BY d.variant ORDER BY 1""", (a.ROBUST, a.ROBUST))
        party = dict(cur.fetchall())
out = [a.contrast(robust, "B3", v, "seats_per_100", rng) for v in ("B0", "B4", "B3-KM", "B4-KM", "ASK-ONLY", "B5", "B5-RANDOM")]
a.holm(out)
for r in out:
    print(f"seats/100 {r['variant']:>9} vs B3 {r['mean_difference']:+.3f} t[{r['t_ci'][0]:+.3f},{r['t_ci'][1]:+.3f}] p={r['wilcoxon_p']:.2g} holm={r['holm_p']:.2g} up/down {r['seeds_up']}/{r['seeds_down']}")
print("mean seats per completed booking:", {k: round(v, 4) for k, v in party.items()})
json.dump({"robustness_seat_weighted_vs_B3": out, "mean_party_completed": party}, open("/audit/audit_seats.json", "w"), indent=2)
