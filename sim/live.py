"""One cell of a sweep, simulated on request: the live what-if page (feature 87).

Exploratory only. Nothing is written anywhere, and nothing here is a report figure:
those are counted from the event log by metrics.sql. A cell is built as
experiments/sweep.py builds it (sim.config.apply_cell), and its variant and baseline are
run on the same seeds. Two measures are counted from the events in memory, as metrics.sql
counts them, which a test checks on the same events: completed rides per 100 searches
(primary_metric) and the late share of cancellations (late_cancellation_share).

Only a sweep whose configuration says how far a value may go live (``live: {min, max}``)
is offered, and only its one parameter may change.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Mapping

import yaml

from vayora_events.vocabulary import EventType
from sim.config import ConfigError, apply_cell, load_variant, load_world, validate, validate_variant
from sim.frozen import SEEDS_PER_VARIANT
from sim.simulator import Simulation

CONFIGS = Path(__file__).resolve().parents[1] / "experiments" / "configs"
LATE = timedelta(hours=24)
MEASURES = ("per_100", "late_share")


@dataclass(frozen=True)
class LiveSweep:
    name: str
    parameter: str
    baseline: str
    variant: str
    baseline_per_cell: bool
    low: float
    high: float
    whole: bool


def sweeps(directory: Path = CONFIGS / "sweeps") -> dict[str, LiveSweep]:
    """The sweeps that may be run live, by name."""
    found = {}
    for path in sorted(directory.glob("*.yaml")):
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
        live = raw.get("live")
        if live is None:
            continue
        if set(live) != {"min", "max"} or len(raw["parameters"]) != 1 or live["min"] > live["max"]:
            raise ConfigError(f"{path.name}: live needs a min no more than its max, and one parameter")
        ((parameter, values),) = raw["parameters"].items()
        found[raw["name"]] = LiveSweep(
            raw["name"], parameter, raw["baseline"], raw["variant"], raw.get("baseline_per_cell", False),
            float(live["min"]), float(live["max"]), all(isinstance(v, int) for v in values))
    return found


class _Memory:
    """A sink that keeps the events instead of writing them."""

    def __init__(self) -> None:
        self.events: list[tuple[str, Mapping[str, Any], datetime]] = []

    def write(self, event_type: EventType | str, payload: Mapping[str, Any] | None = None, *,
              sim_time: datetime, actor_type: str | None = None, actor_id: int | None = None) -> None:
        self.events.append((EventType(event_type).value, dict(payload or {}), sim_time))


def measures(events: list[tuple[str, Mapping[str, Any], datetime]]) -> dict[str, float | None]:
    """One seed's measures, as metrics.sql defines them; None where it gives no number."""
    searches = completed = cancelled = late = 0
    for event_type, payload, at in events:
        if event_type == EventType.SEARCH_ISSUED:
            searches += 1
        elif event_type == EventType.TRIP_COMPLETED:
            completed += 1
        elif event_type == EventType.BOOKING_CANCELLED:
            cancelled += 1
            departs = payload.get("departure_time")
            # Without a departure time a cancellation is counted but never late, as in SQL.
            if departs is not None and datetime.fromisoformat(departs) - at < LATE:
                late += 1
    return {"per_100": 100 * completed / searches if searches else None,
            "late_share": late / cancelled if cancelled else None}


def simulate(world: Any, variant: Any, seed: int) -> dict[str, float | None]:
    sink = _Memory()
    Simulation(world, variant, seed, sink).run()
    return measures(sink.events)


def run(sweep: LiveSweep, value: float, seeds: int, world_path: Path = CONFIGS / "world.yaml") -> dict[str, Any]:
    """The sweep's variant and baseline at this value of its parameter, on seeds 1 to ``seeds``."""
    if not 2 <= seeds <= SEEDS_PER_VARIANT:
        raise ValueError(f"seeds must be 2 to {SEEDS_PER_VARIANT}")
    if not sweep.low <= value <= sweep.high:
        raise ValueError(f"{sweep.parameter} must be from {sweep.low:g} to {sweep.high:g} here")
    if sweep.whole and value != int(value):
        raise ValueError(f"{sweep.parameter} must be a whole number")
    value = int(value) if sweep.whole else float(value)

    world = load_world(world_path)
    baseline, variant = load_variant(CONFIGS / sweep.baseline), load_variant(CONFIGS / sweep.variant)
    changed, varied = apply_cell(world, variant, {sweep.parameter: value})
    validate(changed)
    validate_variant(varied)
    reference = changed if sweep.baseline_per_cell else world
    pairs = [(simulate(reference, baseline, seed), simulate(changed, varied, seed))
             for seed in range(1, seeds + 1)]
    return {
        "sweep": sweep.name,
        "parameter": sweep.parameter,
        "value": value,
        "variant": varied.name,
        "baseline": baseline.name,
        "baseline_per_cell": sweep.baseline_per_cell,
        "seeds": seeds,
        "data": "synthetic (simulator), run live: exploratory, not a report figure, kept nowhere",
        "measures": {name: _compare([b[name] for b, _ in pairs], [v[name] for _, v in pairs])
                     for name in MEASURES},
    }


def _compare(baseline: list[float | None], variant: list[float | None]) -> dict[str, Any] | None:
    """Means and the mean difference over the seeds where both have a number. No interval."""
    both = [(b, v) for b, v in zip(baseline, variant, strict=True) if b is not None and v is not None]
    if not both:
        return None
    differences = [v - b for b, v in both]
    return {
        "baseline_mean": statistics.fmean(b for b, _ in both),
        "mean": statistics.fmean(v for _, v in both),
        "difference": statistics.fmean(differences),
        "seeds": len(both),
        "seeds_up": sum(1 for d in differences if d > 0),
        "seeds_down": sum(1 for d in differences if d < 0),
    }
