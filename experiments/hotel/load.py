"""Loads the public hotel bookings into the event log, as a booking of a seat is logged.

    python -m experiments.hotel.load --run-id <uuid>

Not VAYORA data and not ride-sharing: experiments/configs/hotel.yaml names the source.
Only bookings made within the booking horizon are loaded. Each becomes the events a
booking makes on the platform: requested and accepted on the day it was made, cancelled
by its guest on the day they cancelled, if they did, and a no-show at arrival if the
guest never came. The variant is the hotel (HOTEL-CITY, HOTEL-RESORT) and the seed the
year of arrival, so each metric query gives one row per year.

The file is downloaded once into experiments/data/, which is not committed, and refused
unless its SHA-256 matches the configuration. Needs VAYORA_DB_URL.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
import shutil
import urllib.request
import uuid
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator

import psycopg
import yaml
from sqlalchemy import make_url

from vayora_events import EventWriter

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "experiments" / "configs" / "hotel.yaml"
DATA = ROOT / "experiments" / "data" / "hotels.csv"

VARIANTS = {"City Hotel": "HOTEL-CITY", "Resort Hotel": "HOTEL-RESORT"}
MONTHS = {name: number for number, name in enumerate(
    ["January", "February", "March", "April", "May", "June", "July", "August",
     "September", "October", "November", "December"], start=1)}


@dataclass(frozen=True)
class HotelConfig:
    url: str
    sha256: str
    horizon_days: int
    hour_utc: int


@dataclass(frozen=True)
class Event:
    variant: str
    seed: int
    event_type: str
    sim_time: datetime
    payload: dict[str, Any]


def load_config(path: Path = CONFIG) -> HotelConfig:
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    if set(raw) != {"source", "horizon_days", "hour_utc"} or set(raw["source"]) != {"url", "sha256"}:
        raise ValueError(f"{path.name}: expected exactly source (url, sha256), horizon_days and hour_utc")
    config = HotelConfig(raw["source"]["url"], raw["source"]["sha256"], int(raw["horizon_days"]),
                         int(raw["hour_utc"]))
    if config.horizon_days < 0 or not 0 <= config.hour_utc <= 23:
        raise ValueError(f"{path.name}: horizon_days must be 0 or more and hour_utc 0 to 23")
    return config


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(config: HotelConfig, dest: Path = DATA) -> Path:
    """The file, downloaded if it is not here yet. Refused unless its hash is the one configured."""
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        partial = dest.with_name(dest.name + ".part")
        with urllib.request.urlopen(config.url, timeout=120) as response, partial.open("wb") as out:
            shutil.copyfileobj(response, out)
        if sha256(partial) != config.sha256:
            partial.unlink()
            raise ValueError(f"{config.url} did not give the file configured: its SHA-256 differs")
        partial.replace(dest)
    found = sha256(dest)
    if found != config.sha256:
        raise ValueError(f"{dest} has SHA-256 {found}, not the {config.sha256} configured: refusing it")
    return dest


def events(rows: Iterable[dict[str, str]], config: HotelConfig) -> Iterator[Event]:
    """The events of each booking made within the horizon, in the file's order.

    A booking's id is its line in the file, counting the first booking as 1.
    """
    for number, row in enumerate(rows, start=1):
        lead = int(row["lead_time"])
        if lead > config.horizon_days:
            continue
        arrival = date(int(row["arrival_date_year"]), MONTHS[row["arrival_date_month"]],
                       int(row["arrival_date_day_of_month"]))
        departure = _at(arrival, config)
        made = departure - timedelta(days=lead)
        where = (VARIANTS[row["hotel"]], arrival.year)
        booking = {"booking_id": number}
        yield Event(*where, "booking_requested", made,
                    booking | {"seats": 1, "departure_time": departure.isoformat()})
        yield Event(*where, "booking_accepted", made, booking)
        status = row["reservation_status"]
        if status == "Canceled":
            cancelled = _at(date.fromisoformat(row["reservation_status_date"]), config)
            if not made <= cancelled <= departure:
                raise ValueError(f"booking {number} was cancelled outside its own lifetime")
            yield Event(*where, "booking_cancelled", cancelled,
                        booking | {"seats": 1, "departure_time": departure.isoformat(),
                                   "cancelled_by": "passenger"})
        elif status == "No-Show":
            yield Event(*where, "trip_failed", departure, booking | {"reason": "passenger_no_show"})
        elif status != "Check-Out":
            raise ValueError(f"booking {number} has an unknown reservation_status {status!r}")


def _at(day: date, config: HotelConfig) -> datetime:
    return datetime.combine(day, time(config.hour_utc), tzinfo=timezone.utc)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--run-id", type=uuid.UUID, required=True)
    args = parser.parse_args()

    url = os.environ.get("VAYORA_DB_URL")
    if not url:
        parser.error("VAYORA_DB_URL is not set")
    config = load_config()
    grouped: dict[tuple[str, int], list[Event]] = defaultdict(list)
    with fetch(config).open(newline="", encoding="utf-8") as file:
        for event in events(csv.DictReader(file), config):
            grouped[(event.variant, event.seed)].append(event)

    dsn = make_url(url).set(drivername="postgresql").render_as_string(hide_password=False)
    with psycopg.connect(dsn) as conn:
        for (variant, seed), batch in sorted(grouped.items()):
            with EventWriter(conn, run_id=args.run_id, variant=variant, seed=seed) as writer:
                for event in batch:
                    writer.write(event.event_type, event.payload, sim_time=event.sim_time)
            print(f"run_id={args.run_id} variant={variant} seed={seed} events={len(batch)}")


if __name__ == "__main__":
    main()
