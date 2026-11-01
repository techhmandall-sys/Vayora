import uuid
from datetime import datetime, timedelta, timezone

import pytest

from experiments.analysis.queries import run_query
from vayora_events import EventType, EventWriter

RUN_ID = uuid.UUID("33333333-3333-3333-3333-333333333333")
T0 = datetime(2026, 1, 5, 9, 0, tzinfo=timezone.utc)


def _count(conn):
    return conn.execute("SELECT COUNT(*) FROM events").fetchone()[0]


def _writer(conn, **kwargs):
    return EventWriter(conn, run_id=RUN_ID, variant="B0", seed=7, **kwargs)


def test_row_is_written_with_run_context_actor_and_payload(pg_conn):
    with _writer(pg_conn) as writer:
        writer.write(
            EventType.BOOKING_CANCELLED,
            {"booking_id": 42, "departure_time": "2026-01-06T09:00:00+00:00"},
            sim_time=T0,
            actor_type="passenger",
            actor_id=1001,
        )

    row = pg_conn.execute(
        "SELECT run_id, variant, seed, sim_time, actor_type, actor_id, event_type, payload "
        "FROM events"
    ).fetchone()
    assert row == (
        RUN_ID, "B0", 7, T0, "passenger", 1001, "booking_cancelled",
        {"booking_id": 42, "departure_time": "2026-01-06T09:00:00+00:00"},
    )


def test_payload_defaults_to_empty_object(pg_conn):
    with _writer(pg_conn) as writer:
        writer.write(EventType.SEARCH_ISSUED, sim_time=T0)

    assert pg_conn.execute("SELECT payload FROM events").fetchone()[0] == {}


def test_events_are_flushed_in_batches(pg_conn):
    writer = _writer(pg_conn, batch_size=3)
    for i in range(7):
        writer.write(EventType.SEARCH_ISSUED, {"i": i}, sim_time=T0 + timedelta(minutes=i))

    assert _count(pg_conn) == 6  # two full batches; one event still buffered
    writer.flush()
    assert _count(pg_conn) == 7
    writer.flush()  # nothing buffered: a no-op
    assert _count(pg_conn) == 7


def test_buffered_events_survive_an_exception_in_the_caller(pg_conn):
    with pytest.raises(RuntimeError):
        with _writer(pg_conn) as writer:
            writer.write(EventType.SEARCH_ISSUED, sim_time=T0)
            raise RuntimeError("simulation crashed")

    assert _count(pg_conn) == 1


def test_event_type_outside_the_vocabulary_is_rejected(pg_conn):
    writer = _writer(pg_conn)
    with pytest.raises(ValueError):
        writer.write("seat_teleported", sim_time=T0)


def test_event_type_may_be_given_as_its_string_value(pg_conn):
    with _writer(pg_conn) as writer:
        writer.write("trip_completed", sim_time=T0)

    assert pg_conn.execute("SELECT event_type FROM events").fetchone()[0] == "trip_completed"


def test_naive_sim_time_is_rejected(pg_conn):
    writer = _writer(pg_conn)
    with pytest.raises(ValueError):
        writer.write(EventType.SEARCH_ISSUED, sim_time=datetime(2026, 1, 5, 9, 0))


def test_written_events_are_counted_by_the_primary_metric(pg_conn, sa_conn):
    with _writer(pg_conn) as writer:
        for _ in range(4):
            writer.write(EventType.SEARCH_ISSUED, sim_time=T0)
        writer.write(EventType.TRIP_COMPLETED, sim_time=T0 + timedelta(days=2))

    (row,) = run_query(sa_conn, "primary_metric", run_id=RUN_ID, variant="B0")

    assert (row["seed"], row["searches"], row["completed"]) == (7, 4, 1)
    assert float(row["per_100"]) == 25.0
