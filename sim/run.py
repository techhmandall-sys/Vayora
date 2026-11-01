"""Runs one variant over a set of seeds and writes the events to PostgreSQL.

    python -m sim.run --variant experiments/configs/variants/B0.yaml

Needs VAYORA_DB_URL. Prints the run id; pass it to the metric queries.
"""

from __future__ import annotations

import argparse
import os
import uuid
from pathlib import Path

import psycopg
from sqlalchemy import make_url

from vayora_events import EventWriter
from sim.config import load_variant, load_world
from sim.frozen import SEEDS_PER_VARIANT
from sim.simulator import Simulation

CONFIGS = Path(__file__).resolve().parent.parent / "experiments" / "configs"


def run_variant(conn: psycopg.Connection, world_path: Path, variant_path: Path,
                seeds: range, run_id: uuid.UUID) -> None:
    world, variant = load_world(world_path), load_variant(variant_path)
    for seed in seeds:
        with EventWriter(conn, run_id=run_id, variant=variant.name, seed=seed) as writer:
            Simulation(world, variant, seed, writer).run()


def _seed_range(text: str) -> range:
    first, _, last = text.partition("-")
    return range(int(first), int(last or first) + 1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--world", type=Path, default=CONFIGS / "world.yaml")
    parser.add_argument("--variant", type=Path, required=True)
    parser.add_argument("--seeds", type=_seed_range, default=range(1, SEEDS_PER_VARIANT + 1),
                        help="a seed or an inclusive range such as 1-30")
    parser.add_argument("--run-id", type=uuid.UUID, default=None,
                        help="reuse a run id to add a variant to an existing run")
    args = parser.parse_args()

    url = os.environ.get("VAYORA_DB_URL")
    if not url:
        parser.error("VAYORA_DB_URL is not set")
    dsn = make_url(url).set(drivername="postgresql").render_as_string(hide_password=False)
    run_id = args.run_id or uuid.uuid4()
    with psycopg.connect(dsn) as conn:
        run_variant(conn, args.world, args.variant, args.seeds, run_id)
    print(f"run_id={run_id} variant={args.variant.stem} "
          f"seeds={args.seeds.start}-{args.seeds.stop - 1}")


if __name__ == "__main__":
    main()
