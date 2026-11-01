"""A live what-if run counts as the reports count, and reproduces a sweep it overlaps.

The live run (sim/live.py) counts its two measures in memory, so they are checked here
against metrics.sql on the very same events, and against a committed sweep report.
"""

import json
import uuid
from pathlib import Path

import pytest

from experiments.analysis.queries import run_query
from vayora_events import EventWriter
from sim import live
from sim.config import load_variant, load_world
from sim.simulator import Simulation

ROOT = Path(__file__).resolve().parents[2]
RUN_ID = uuid.UUID("33333333-3333-3333-3333-333333333333")


def test_the_live_measures_are_the_metric_queries_on_the_same_events(pg_conn, sa_conn):
    world = load_world(live.CONFIGS / "world.yaml")
    variant = load_variant(live.CONFIGS / "variants" / "B4.yaml")
    sink = live._Memory()
    Simulation(world, variant, 1, sink).run()
    with EventWriter(pg_conn, run_id=RUN_ID, variant="B4", seed=1) as writer:
        for event_type, payload, at in sink.events:
            writer.write(event_type, payload, sim_time=at)

    counted = live.measures(sink.events)
    (primary,) = run_query(sa_conn, "primary_metric", run_id=RUN_ID, variant="B4")
    (late,) = run_query(sa_conn, "late_cancellation_share", run_id=RUN_ID, variant="B4")

    assert counted["per_100"] == pytest.approx(float(primary["per_100"]), abs=1e-12)
    assert counted["late_share"] == pytest.approx(float(late["late_share"]), abs=1e-12)


def test_a_live_run_at_a_value_a_sweep_covered_reproduces_that_sweep():
    report = json.loads((ROOT / "experiments/reports/sweep-flexible-booking-5eeb0000-0000-4000-8000-000000000008.json")
                        .read_text(encoding="utf-8"))
    (cell,) = [c for c in report["cells"] if c["parameters"] == {"demand.flexible_booking.share": 0.5}]

    result = live.run(live.sweeps()["flexible-booking"], 0.5, 30)

    for name in live.MEASURES:
        assert result["measures"][name]["mean"] == pytest.approx(cell["measures"][name]["mean"], abs=1e-9)
        assert result["measures"][name]["baseline_mean"] == pytest.approx(cell["measures"][name]["baseline_mean"],
                                                                          abs=1e-9)
        assert result["measures"][name]["seeds_up"] == cell["measures"][name]["vs_baseline"]["seeds_improved"]
