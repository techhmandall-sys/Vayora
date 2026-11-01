"""E3a: does the served durability model recover the hazard that generated the data?

Re-simulates the validation seeds (experiments/configs/hazard.yaml) under B0, in memory, and
for every accepted booking compares three probabilities that it lasts until departure:

- the model's (h3-cox, h3-km), from the features the platform saw at the request;
- the truth, from the booking's hidden type, last-mile gap and the shock process, integrated
  over when a shock might hit (exactly the generator in sim/hazard.py), which no model can see;
- what happened.

Writes research_audit/data/truth_recovery.json. Reads nothing from the database and writes
nothing else; the simulator is deterministic, so this is the validation data train.py used.

    python research_audit/scripts/truth_recovery.py       (inside the research container)
"""

from __future__ import annotations

import json
import math
from datetime import datetime
from pathlib import Path

import numpy as np
from scipy import stats

from experiments.hazard.train import CONFIGS, load_config, seeds
from intel.app.hazard.features import FeatureTracker
from intel.app.hazard.model import MODEL_DIR, HazardModel
from sim.config import load_variant, load_world
from sim.simulator import MemorySink, Simulation

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "research_audit" / "data" / "truth_recovery.json"


def true_survival(hours: float, shape: float, scale: float, rate: float, multiplier: float) -> float:
    """P(no passenger cancellation within `hours` of acceptance), averaged over the shock time."""
    if hours <= 0:
        return 1.0
    whole = (hours / scale) ** shape
    no_shock = math.exp(-rate * hours) * math.exp(-whole)
    if rate <= 0:
        return no_shock
    u = np.linspace(0.0, hours, 801)
    before = (u / scale) ** shape
    # A shock at u leaves the hazard as it was until u and multiplies it after (sim/hazard.py).
    density = rate * np.exp(-rate * u) * np.exp(-(before + multiplier * (whole - before)))
    return float(no_shock + np.trapezoid(density, u))


def rows(world, variant, seed, cox: HazardModel, km: HazardModel) -> list[dict]:
    sink = MemorySink()
    sim = Simulation(world, variant, seed, sink)
    sim.run()
    shock = world.demand.shock
    rate, multiplier = shock.rate_per_day / 24.0, shock.hazard_multiplier
    tracker, desired, requested, live, out = FeatureTracker(), {}, {}, {}, []
    for event in sink.events:
        kind, payload = event.event_type, event.payload
        booking = payload.get("booking_id")
        if kind == "search_issued":
            desired[payload["search_id"]] = datetime.fromisoformat(payload["desired_departure"])
        elif kind == "booking_requested":
            departure = datetime.fromisoformat(payload["departure_time"])
            # As experiments/hazard/dataset.py: read before this event is observed.
            features = tracker.features(
                passenger_id=payload["passenger_id"], seats=payload["seats"], now=event.sim_time,
                departure=departure, desired_departure=desired[payload["search_id"]],
                last_mile_distance_m=payload.get("last_mile_distance_m", 0.0),
                last_mile_mode_available=payload.get("last_mile_mode_available", False))
            requested[booking] = (features, departure)
        elif kind == "booking_accepted" and booking in requested:
            features, departure = requested.pop(booking)
            live[booking] = (features, event.sim_time, departure)
        elif kind in ("booking_cancelled", "trip_started") and booking in live:
            features, accepted, departure = live.pop(booking)
            horizon = (departure - accepted).total_seconds() / 3600.0
            passenger = sim._passenger_of[booking]
            hidden = world.demand.types[passenger.kind]
            scale = hidden.weibull_scale_hours / (1.0 + hidden.last_mile_sensitivity * passenger.last_mile_offset_m / 1000.0)
            out.append({
                "seed": seed,
                "horizon": horizon,
                "duration": (event.sim_time - accepted).total_seconds() / 3600.0,
                "cancelled": kind == "booking_cancelled" and payload.get("cancelled_by") == "passenger",
                "reached_departure": kind == "trip_started",
                "true": true_survival(horizon, hidden.weibull_shape, scale, rate, multiplier),
                "cox": cox.survival(features, horizon),
                "km": km.survival(features, horizon),
            })
        tracker.observe(kind, payload)
    return out


def auc(score_of_lasting: np.ndarray, lasted: np.ndarray) -> float:
    """Probability a booking that lasted was given a higher survival than one that was cancelled."""
    ranks = stats.rankdata(score_of_lasting)
    n1, n0 = lasted.sum(), (~lasted).sum()
    return float((ranks[lasted].sum() - n1 * (n1 + 1) / 2) / (n1 * n0))


def main() -> None:
    config = load_config()
    world = load_world(CONFIGS / config["world"])
    variant = load_variant(CONFIGS / config["variant"])
    version = config["version"]
    cox = HazardModel.load(MODEL_DIR / f"{version}-cox.json")
    km = HazardModel.load(MODEL_DIR / f"{version}-km.json")
    data = [r for seed in seeds(config["validation_seeds"]) for r in rows(world, variant, seed, cox, km)]

    known = [r for r in data if r["cancelled"] or r["reached_departure"]]
    lasted = np.array([r["reached_departure"] for r in known])
    p = {name: np.array([r[name] for r in known]) for name in ("true", "cox", "km")}

    report: dict = {
        "version": version, "world": config["world"], "variant": config["variant"],
        "validation_seeds": seeds(config["validation_seeds"]),
        "bookings": len(data), "known_outcome": len(known),
        "observed_survival_to_departure": float(lasted.mean()),
        "models": {},
    }
    for name in ("true", "cox", "km"):
        report["models"][name] = {
            "mean_survival": float(p[name].mean()),
            "brier": float(np.mean((p[name] - lasted) ** 2)),
            "auc_lasting": auc(p[name], lasted),
        }
    for name in ("cox", "km"):
        gap = p[name] - p["true"]
        order = np.argsort(p[name], kind="stable")
        report["models"][name].update({
            "mean_abs_error_vs_truth": float(np.abs(gap).mean()),
            "spearman_vs_truth": float(stats.spearmanr(p[name], p["true"]).statistic),
            "share_of_achievable_auc": float((auc(p[name], lasted) - 0.5) / (auc(p["true"], lasted) - 0.5)),
            "calibration_vs_truth": [
                {"bookings": int(len(part)), "mean_predicted": float(p[name][part].mean()),
                 "mean_true": float(p["true"][part].mean()), "observed": float(lasted[part].mean())}
                for part in np.array_split(order, 10)],
        })
    durations = np.array([r["duration"] for r in data])
    report["at_risk_from"] = {str(t): int((durations >= t).sum()) for t in (48, 96, 120, 144, 150, 156, 160, 163)}

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {report['bookings']} bookings, {report['known_outcome']} with a known outcome")
    print(f"observed survival to departure {report['observed_survival_to_departure']:.3f}")
    for name, m in report["models"].items():
        extra = "" if name == "true" else (f"  |pred-true| {m['mean_abs_error_vs_truth']:.3f}  spearman {m['spearman_vs_truth']:.3f}"
                                           f"  share of achievable AUC {m['share_of_achievable_auc']:.2f}")
        print(f"{name:>5}: mean {m['mean_survival']:.3f}  brier {m['brier']:.4f}  AUC {m['auc_lasting']:.3f}{extra}")
    for name in ("cox", "km"):
        low = report["models"][name]["calibration_vs_truth"][0]
        print(f"{name:>5} lowest tenth: predicted {low['mean_predicted']:.3f}  true {low['mean_true']:.3f}  observed {low['observed']:.3f}")
    print("at risk from hour t:", report["at_risk_from"])


if __name__ == "__main__":
    main()
