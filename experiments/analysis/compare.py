"""Compares variants of one run, seed by seed, from the event log.

    python -m experiments.analysis.compare --run-id <uuid> --baseline B0 B3 B4

Every figure comes from a query in metrics.sql. Variants are paired by seed: the same
seed is the same world, so the per-seed difference removes world-to-world noise.
Writes experiments/reports/ladder-<run id>.json and .md.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import uuid
from pathlib import Path
from typing import Any

from scipy import stats
from sqlalchemy import Connection, create_engine, make_url

from experiments.analysis.queries import run_query

REPORTS = Path(__file__).resolve().parents[1] / "reports"

# (query, column, label, decimals)
MEASURES = [
    ("primary_metric", "per_100", "Completed rides per 100 searches", 2),
    ("late_cancellation_share", "late_share", "Late share of cancellations", 3),
    ("funnel_rates", "booking_rate", "Booking requests per search", 3),
    ("intervention_volume", "contact_rate", "Reconfirmation requests per accepted booking", 3),
    ("intervention_volume", "refilled", "Released seats refilled", 1),
    ("refill_additionality", "refills_only_option",
     "Refills by a passenger offered no other ride", 1),
    ("refill_additionality", "empty_share", "Searches offered no ride", 3),
    ("empty_search_reasons", "no_ride_share", "Searches offered no ride: none left at that time", 3),
    ("empty_search_reasons", "full_share", "Searches offered no ride: every ride then full", 3),
    ("refill_by_release_timing", "early_refill_share",
     "Share of seats refilled, rides with only early releases", 3),
    ("refill_by_release_timing", "late_refill_share",
     "Share of seats refilled, rides with only late releases", 3),
]


def per_seed(conn: Connection, run_id: uuid.UUID, variant: str) -> dict[str, dict[int, float]]:
    """{column: {seed: value}} for every measure; undefined values are left out."""
    out: dict[str, dict[int, float]] = {}
    for query in {m[0] for m in MEASURES}:
        rows = run_query(conn, query, run_id=run_id, variant=variant)
        for _, column, _, _ in (m for m in MEASURES if m[0] == query):
            out[column] = {r["seed"]: float(r[column]) for r in rows if r[column] is not None}
    return out


def paired(baseline: dict[int, float], variant: dict[int, float],
           confidence: float = 0.95) -> dict[str, Any] | None:
    """Mean per-seed difference, its t interval and a Wilcoxon signed-rank test."""
    seeds = sorted(set(baseline) & set(variant))
    if len(seeds) < 2:
        return None
    differences = [variant[s] - baseline[s] for s in seeds]
    mean = statistics.fmean(differences)
    spread = statistics.stdev(differences)
    half = stats.t.ppf(0.5 + confidence / 2, len(seeds) - 1) * spread / len(seeds) ** 0.5
    if any(differences):
        p_value = float(stats.wilcoxon(differences).pvalue)
    else:
        p_value = 1.0  # identical on every seed
    return {
        "seeds": len(seeds),
        "mean_difference": mean,
        "ci_low": mean - half,
        "ci_high": mean + half,
        "confidence": confidence,
        "wilcoxon_p": p_value,
        "seeds_improved": sum(1 for d in differences if d > 0),
        "seeds_worsened": sum(1 for d in differences if d < 0),
    }


def build_report(conn: Connection, run_id: uuid.UUID, baseline: str,
                 variants: list[str]) -> dict[str, Any]:
    data = {name: per_seed(conn, run_id, name) for name in [baseline, *variants]}
    report: dict[str, Any] = {
        "run_id": str(run_id), "baseline": baseline, "data": "synthetic (simulator)",
        "variants": {},
    }
    for name, measures in data.items():
        entry: dict[str, Any] = {}
        for _, column, label, _ in MEASURES:
            values = measures[column]
            entry[column] = {
                "label": label,
                "seeds": len(values),
                "mean": statistics.fmean(values.values()) if values else None,
                "vs_baseline": None if name == baseline
                else paired(data[baseline][column], values),
            }
        report["variants"][name] = entry
    return report


def to_markdown(report: dict[str, Any]) -> str:
    baseline = report["baseline"]
    lines = [
        f"# Variant comparison, run `{report['run_id']}`", "",
        "Synthetic data from the simulator. Differences are per seed against "
        f"**{baseline}**, with a 95% t interval and a Wilcoxon signed-rank p-value.", "",
    ]
    for _, column, label, decimals in MEASURES:
        lines += [f"## {label}", "",
                  f"| Variant | Seeds | Mean | Difference vs {baseline} | 95% CI | p | Seeds up / down |",
                  "|---|---|---|---|---|---|---|"]
        for name, entry in report["variants"].items():
            cell = entry[column]
            mean = "undefined" if cell["mean"] is None else f"{cell['mean']:.{decimals}f}"
            diff = cell["vs_baseline"]
            if diff is None:
                lines.append(f"| {name} | {cell['seeds']} | {mean} | | | | |")
            else:
                lines.append(
                    f"| {name} | {cell['seeds']} | {mean} "
                    f"| {diff['mean_difference']:+.{decimals}f} "
                    f"| {diff['ci_low']:+.{decimals}f} to {diff['ci_high']:+.{decimals}f} "
                    f"| {_p(diff['wilcoxon_p'])} "
                    f"| {diff['seeds_improved']} / {diff['seeds_worsened']} |")
        lines.append("")
    return "\n".join(lines)


def _p(value: float) -> str:
    return "<0.0001" if value < 0.0001 else f"{value:.4f}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run-id", type=uuid.UUID, required=True)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("variants", nargs="+")
    args = parser.parse_args()

    url = os.environ.get("VAYORA_DB_URL")
    if not url:
        parser.error("VAYORA_DB_URL is not set")
    engine = create_engine(make_url(url).set(drivername="postgresql+psycopg"))
    with engine.connect() as conn:
        report = build_report(conn, args.run_id, args.baseline, args.variants)

    REPORTS.mkdir(exist_ok=True)
    stem = REPORTS / f"ladder-{args.run_id}"
    stem.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    stem.with_suffix(".md").write_text(to_markdown(report) + "\n", encoding="utf-8")
    print(to_markdown(report))


if __name__ == "__main__":
    main()
