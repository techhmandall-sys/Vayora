"""Sets the hotels' cancellations beside the simulator's, a day at a time over the last week.

    python -m experiments.hotel.report --run-id <hotel run> --simulated <run> B0

Every figure comes from the daily_cancellation_hazard query in metrics.sql, run on the
hotel bookings and on one variant of a simulator run alike. A day's hazard pools the
seeds (for the hotels, the years of arrival): every cancellation on that day over every
booking standing on it. Writes experiments/reports/hotel-<run id>.json and .md.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import uuid
from pathlib import Path
from typing import Any

from sqlalchemy import Connection, create_engine, make_url

from experiments.analysis.queries import run_query

REPORTS = Path(__file__).resolve().parents[1] / "reports"
HOTELS = [("HOTEL-CITY", "City hotel"), ("HOTEL-RESORT", "Resort hotel")]
DAYS = range(7, -1, -1)
# Survival is compared from here: in the simulator a ride is published less than 7 days
# ahead, so nobody stands on day 7 there.
FROM_DAY = 6


def survival(standing: dict[int, int], cancelled: dict[int, int]) -> float:
    """Of the bookings standing FROM_DAY days out, the share still standing at departure."""
    return math.prod(1 - cancelled[day] / standing[day] for day in range(FROM_DAY, -1, -1) if standing[day])


def pooled(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """One variant's days, its seeds added together."""
    def days(row_set: list[dict[str, Any]], column: str) -> dict[int, int]:
        return {day: sum(row[f"{column}_d{day}"] for row in row_set) for day in DAYS}

    standing, cancelled = days(rows, "standing"), days(rows, "cancelled")
    accepted = sum(row["accepted"] for row in rows)
    no_shows = sum(row["no_shows"] for row in rows)
    in_week = sum(cancelled.values())
    return {
        "accepted": accepted,
        "no_shows": no_shows,
        "no_show_share": no_shows / accepted if accepted else None,
        "cancelled_in_week": in_week,
        "days": [{"day": day, "standing": standing[day], "cancelled": cancelled[day],
                  "hazard": cancelled[day] / standing[day] if standing[day] else None} for day in DAYS],
        f"survival_from_day_{FROM_DAY}": survival(standing, cancelled),
        "share_on_day_0": cancelled[0] / in_week if in_week else None,
        "share_on_days_0_1": (cancelled[0] + cancelled[1]) / in_week if in_week else None,
        "by_seed": [{"seed": row["seed"], "accepted": row["accepted"],
                     f"survival_from_day_{FROM_DAY}": survival(days([row], "standing"), days([row], "cancelled"))}
                    for row in rows],
    }


def build_report(conn: Connection, run_id: uuid.UUID, simulated_run: uuid.UUID, simulated: str) -> dict[str, Any]:
    hotels = {name: pooled(run_query(conn, "daily_cancellation_hazard", run_id=run_id, variant=name))
              for name, _ in HOTELS}
    for name, figures in hotels.items():
        if not figures["accepted"]:
            raise ValueError(f"run {run_id} has no {name} bookings: load them first")
    return {
        "run_id": str(run_id),
        "data": "real: Antonio, de Almeida and Nunes (2019), Hotel booking demand datasets, "
                "Data in Brief 22, 41-49, CC BY 4.0; bookings made at most 7 days before arrival",
        "simulated": {"run_id": str(simulated_run), "variant": simulated},
        "measure": "passenger (guest) cancellations by whole days before departure (arrival), "
                   "over bookings standing that day",
        "variants": hotels | {simulated: pooled(run_query(conn, "daily_cancellation_hazard",
                                                          run_id=simulated_run, variant=simulated))},
    }


def markdown(report: dict[str, Any]) -> str:
    simulated = report["simulated"]["variant"]
    columns = [(name, label) for name, label in HOTELS] + [(simulated, f"Simulated {simulated}")]
    variants = report["variants"]

    def percent(value: float | None, places: int = 1) -> str:
        return "undefined" if value is None else f"{100 * value:.{places}f}%"

    lines = [
        "# Real hotel cancellations beside the simulator's",
        "",
        f"Hotel run `{report['run_id']}`: {report['data']}.",
        f"Simulated: run `{report['simulated']['run_id']}`, variant {simulated}, every seed.",
        "",
        "The hotel data has dates, not times: day 0 is the day of arrival, whatever the hour. "
        "In the simulator a day is 24 hours counted back from departure.",
        "",
        "## Daily hazard: of the bookings standing that day, the share cancelled",
        "",
        "| Days before departure | " + " | ".join(label for _, label in columns) + " |",
        "|---" * (len(columns) + 1) + "|",
    ]
    for index, day in enumerate(DAYS):
        cells = []
        for name, _ in columns:
            entry = variants[name]["days"][index]
            cells.append(f"{percent(entry['hazard'], 2)} ({entry['cancelled']} of {entry['standing']})")
        lines.append(f"| {day} | " + " | ".join(cells) + " |")
    lines += [
        "",
        "## Over the week",
        "",
        "| | " + " | ".join(label for _, label in columns) + " |",
        "|---" * (len(columns) + 1) + "|",
        "| Bookings accepted | " + " | ".join(str(variants[name]["accepted"]) for name, _ in columns) + " |",
        "| Cancelled within the week | "
        + " | ".join(str(variants[name]["cancelled_in_week"]) for name, _ in columns) + " |",
        "| Of those, on day 0 | " + " | ".join(percent(variants[name]["share_on_day_0"]) for name, _ in columns) + " |",
        "| On day 0 or 1 | " + " | ".join(percent(variants[name]["share_on_days_0_1"]) for name, _ in columns) + " |",
        f"| Standing {FROM_DAY} days out and still standing at departure | "
        + " | ".join(percent(variants[name][f"survival_from_day_{FROM_DAY}"]) for name, _ in columns) + " |",
        "| No-shows, per booking accepted | "
        + " | ".join(percent(variants[name]["no_show_share"]) for name, _ in columns) + " |",
        "",
        "## The hotels year by year",
        "",
        f"| Hotel | Year of arrival | Bookings accepted | Standing {FROM_DAY} days out and at departure |",
        "|---|---|---|---|",
    ]
    for name, label in HOTELS:
        for row in variants[name]["by_seed"]:
            lines.append(f"| {label} | {row['seed']} | {row['accepted']} "
                         f"| {percent(row[f'survival_from_day_{FROM_DAY}'])} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run-id", type=uuid.UUID, required=True, help="the hotel bookings' run")
    parser.add_argument("--simulated", nargs=2, metavar=("RUN_ID", "VARIANT"), required=True)
    args = parser.parse_args()

    url = os.environ.get("VAYORA_DB_URL")
    if not url:
        parser.error("VAYORA_DB_URL is not set")
    engine = create_engine(make_url(url).set(drivername="postgresql+psycopg"))
    with engine.connect() as conn:
        report = build_report(conn, args.run_id, uuid.UUID(args.simulated[0]), args.simulated[1])

    REPORTS.mkdir(exist_ok=True)
    base = REPORTS / f"hotel-{args.run_id}"
    base.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    base.with_suffix(".md").write_text(markdown(report), encoding="utf-8")
    print(f"wrote {base}.json and .md")


if __name__ == "__main__":
    main()
