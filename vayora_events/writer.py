from __future__ import annotations

from datetime import datetime
from typing import Any, Mapping
from uuid import UUID

import psycopg
from psycopg.types.json import Jsonb

from vayora_events.vocabulary import EventType

_COPY = (
    "COPY events (run_id, variant, seed, sim_time, actor_type, actor_id, event_type, payload) "
    "FROM STDIN"
)


class EventWriter:
    """Buffers events for one (run_id, variant, seed) and writes them in batches.

    Unlike the Java writer, this one fails loudly: a simulation that silently drops
    rows produces wrong numbers, so bad input raises and a failed flush propagates.

    Each flush is one COPY and one commit on the given connection. Use as a context
    manager, or call flush() yourself before reading the rows back.
    """

    def __init__(
        self,
        conn: psycopg.Connection,
        *,
        run_id: UUID,
        variant: str,
        seed: int,
        batch_size: int = 5000,
    ) -> None:
        if batch_size < 1:
            raise ValueError("batch_size must be at least 1")
        self._conn = conn
        self._run_id = run_id
        self._variant = variant
        self._seed = seed
        self._batch_size = batch_size
        self._buffer: list[tuple] = []

    def write(
        self,
        event_type: EventType | str,
        payload: Mapping[str, Any] | None = None,
        *,
        sim_time: datetime,
        actor_type: str | None = None,
        actor_id: int | None = None,
    ) -> None:
        event_type = EventType(event_type)  # ValueError outside the fixed vocabulary
        if sim_time.tzinfo is None:
            raise ValueError("sim_time must be timezone-aware")
        self._buffer.append(
            (
                self._run_id,
                self._variant,
                self._seed,
                sim_time,
                actor_type,
                actor_id,
                event_type.value,
                Jsonb(dict(payload) if payload else {}),
            )
        )
        if len(self._buffer) >= self._batch_size:
            self.flush()

    def flush(self) -> None:
        if not self._buffer:
            return
        with self._conn.cursor() as cur:
            with cur.copy(_COPY) as copy:
                for row in self._buffer:
                    copy.write_row(row)
        self._conn.commit()
        self._buffer.clear()

    def __enter__(self) -> EventWriter:
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        # Events buffered before a crash are still real observations; keep them.
        self.flush()
