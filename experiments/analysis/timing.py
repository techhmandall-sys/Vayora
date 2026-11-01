"""When cancellations come, relative to departure, for each variant of one run.

    python -m experiments.analysis.timing --run-id <uuid> --baseline B3 B0 B3 B4 B5 ORACLE

Every figure comes from the cancellation_timing query in metrics.sql: per seed, the
number of cancellations in each band of hours before departure. Variants are paired by
seed against the baseline, as in compare.py. Writes
experiments/reports/timing-<run id>.json.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import uuid
from pathlib import Path
from typing import Any

from sqlalchemy import Connection, create_engine, make_url

from experiments.analysis.compare import paired
from experiments.analysis.queries import run_query

REPORTS = Path(__file__).resolve().parents[1] / "reports"

# The query's columns, nearest departure first, with the labels the report uses.
BANDS = [("under_2h", "under 2 h"), ("h2_6", "2-6 h"), ("h6_12", "6-12 h"), ("h12_24", "12-24 h"),
         ("h24_48", "24-48 h"), ("h48_96", "48-96 h"), ("h96_plus", "96 h or more")]


def per_seed(conn: Connection, run_id: uuid.UUID, variant: str) -> dict[str, dict[int, float]]:
    rows = run_query(conn, "cancellation_timing", run_id=run_id, variant=variant)
    missing = sum(row["missing_departure_time"] for row in rows)
    if missing:
        raise ValueError(f"{variant}: {missing} cancellations carry no departure_time")
    return {band: {row["seed"]: float(row[band]) for row in rows} for band, _ in BANDS}


def build_report(conn: Connection, run_id: uuid.UUID, baseline: str, variants: list[str]) -> dict[str, Any]:
    seeds = {name: per_seed(conn, run_id, name) for name in variants}
    if baseline not in seeds:
        seeds[baseline] = per_seed(conn, run_id, baseline)
    return {
        "run_id": str(run_id),
        "baseline": baseline,
        "data": "synthetic (simulator)",
        "measure": "cancellations per seed, by hours before departure",
        "bands": [label for _, label in BANDS],
        "variants": {
            name: [
                {"band": label,
                 "mean": statistics.fmean(seeds[name][band].values()),
                 "vs_baseline": None if name == baseline else paired(seeds[baseline][band], seeds[name][band])}
                for band, label in BANDS
            ]
            for name in variants
        },
    }


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
    path = REPORTS / f"timing-{args.run_id}.json"
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
