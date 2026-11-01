"""E3b/E3c: the simulated world's cancellation intensity beside the public hotel bookings.

For B0 in each world (the current one, the cancellation-scale worlds, last-mile off) and for
the two hotels, the share of bookings standing 6 days out that still stand at departure, and
the daily cancellation rates, counted exactly as experiments/hotel/report.py counts them
(the same named query, the same pooling). Read-only SQL; writes only
research_audit/data/cancellation_benchmark.json. experiments/hotel/report.py is not used
because it always writes the committed hotel report.
"""

from __future__ import annotations

import json
import os
import uuid
from pathlib import Path

from sqlalchemy import create_engine, make_url

from experiments.analysis.queries import run_query
from experiments.hotel.report import FROM_DAY, pooled

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "research_audit" / "data" / "cancellation_benchmark.json"
SETS = [
    ("hotel: city", "407e1000-0000-4000-8000-000000000001", "HOTEL-CITY"),
    ("hotel: resort", "407e1000-0000-4000-8000-000000000001", "HOTEL-RESORT"),
    ("simulated B0, last-mile off (= world h2)", "7a1c0de0-0000-4000-8000-000000000009", "B0"),
    ("simulated B0, world h3 (scale x1)", "7a1c0de0-0000-4000-8000-000000000003", "B0"),
    ("simulated B0, scale x1.5", "7a1c0de0-0000-4000-8000-000000000006", "B0"),
    ("simulated B0, scale x2", "7a1c0de0-0000-4000-8000-000000000007", "B0"),
    ("simulated B0, scale x3", "7a1c0de0-0000-4000-8000-000000000008", "B0"),
]


def main() -> None:
    engine = create_engine(make_url(os.environ["VAYORA_DB_URL"]).set(drivername="postgresql+psycopg"))
    out = []
    with engine.connect() as conn:
        for label, run_id, variant in SETS:
            week = pooled(run_query(conn, "daily_cancellation_hazard", run_id=uuid.UUID(run_id), variant=variant))
            rates = [d["hazard"] for d in week["days"] if d["day"] <= FROM_DAY and d["hazard"] is not None]
            out.append({"set": label, "run_id": run_id, "variant": variant, "accepted": week["accepted"],
                        f"survival_from_day_{FROM_DAY}": week[f"survival_from_day_{FROM_DAY}"],
                        "daily_rate_min": min(rates), "daily_rate_max": max(rates),
                        "no_show_share": week["no_show_share"]})
    OUT.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    for row in out:
        print(f"{row['set']:<42} standing day {FROM_DAY} -> departure {row[f'survival_from_day_{FROM_DAY}']:.3f}"
              f"   daily {row['daily_rate_min']:.2%} to {row['daily_rate_max']:.2%}   accepted {row['accepted']}")


if __name__ == "__main__":
    main()
