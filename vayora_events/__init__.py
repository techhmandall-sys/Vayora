"""Python side of the VAYORA research event log.

Writes to the same `events` table, with the same vocabulary, as core-api's
EventLogService.
"""

from vayora_events.vocabulary import EventType

__all__ = ["EventType", "EventWriter"]


def __getattr__(name: str):
    # The writer needs a database driver and the vocabulary does not. intel, which runs the
    # simulator for live what-if runs and writes nothing, has no driver.
    if name == "EventWriter":
        from vayora_events.writer import EventWriter

        return EventWriter
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
