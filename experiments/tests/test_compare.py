import uuid

import pytest

from experiments.analysis.compare import build_report, paired, to_markdown
from vayora_events import EventWriter
from sim.config import load_variant, load_world
from sim.run import CONFIGS
from sim.simulator import Simulation


def test_paired_difference_and_interval_match_hand_computed_values():
    baseline = {1: 10.0, 2: 12.0, 3: 11.0, 4: 13.0}
    variant = {1: 11.0, 2: 14.0, 3: 12.0, 4: 16.0, 5: 99.0}  # seed 5 has no pair

    result = paired(baseline, variant)

    # Differences 1, 2, 1, 3: mean 1.75, sd 0.9574, t(0.975, 3) = 3.1824.
    assert result["seeds"] == 4
    assert result["mean_difference"] == pytest.approx(1.75)
    assert result["ci_low"] == pytest.approx(1.75 - 3.1824 * 0.9574 / 2, abs=1e-3)
    assert result["ci_high"] == pytest.approx(1.75 + 3.1824 * 0.9574 / 2, abs=1e-3)
    assert (result["seeds_improved"], result["seeds_worsened"]) == (4, 0)
    assert 0 < result["wilcoxon_p"] <= 1


def test_identical_variants_differ_by_exactly_nothing():
    same = {1: 10.0, 2: 12.0, 3: 11.0}

    result = paired(same, dict(same))

    assert (result["mean_difference"], result["ci_low"], result["ci_high"]) == (0.0, 0.0, 0.0)
    assert result["wilcoxon_p"] == 1.0


def test_one_shared_seed_is_too_few_to_compare():
    assert paired({1: 1.0}, {1: 2.0}) is None


def test_a_real_run_is_compared_from_the_event_log(pg_conn, sa_conn):
    run_id = uuid.uuid4()
    world = load_world(CONFIGS / "world.yaml")
    for name in ("B0", "B4"):
        variant = load_variant(CONFIGS / "variants" / f"{name}.yaml")
        for seed in (1, 2, 3):
            with EventWriter(pg_conn, run_id=run_id, variant=name, seed=seed) as writer:
                Simulation(world, variant, seed, writer).run()

    report = build_report(sa_conn, run_id, "B0", ["B4"])

    b0, b4 = report["variants"]["B0"], report["variants"]["B4"]
    assert b0["per_100"]["seeds"] == b4["per_100"]["seeds"] == 3
    assert b0["per_100"]["vs_baseline"] is None
    assert b4["per_100"]["vs_baseline"]["seeds"] == 3
    # B0 never contacts anyone; B4 does.
    assert b0["contact_rate"]["mean"] == 0.0
    assert b4["contact_rate"]["mean"] > 0.0
    markdown = to_markdown(report)
    assert "Completed rides per 100 searches" in markdown and "| B4 | 3 |" in markdown
