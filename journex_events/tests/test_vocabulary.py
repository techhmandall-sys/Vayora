import re
from pathlib import Path

from journex_events import EventType

FROZEN_VOCABULARY = [
    "search_issued", "candidates_returned", "candidate_ranked", "booking_requested",
    "booking_accepted", "booking_rejected", "hazard_scored", "tier_selected",
    "driver_tier_selected", "intervention_issued", "intervention_responded",
    "booking_cancelled", "seat_refilled", "recovery_search", "recovery_booked",
    "supply_gap_detected", "nudge_issued", "nudge_dismissed", "nudge_converted",
    "trip_started", "trip_completed", "trip_failed",
    "last_mile_shown", "last_mile_option_selected",
]

JAVA_ENUM = (
    Path(__file__).parents[2]
    / "core-api/src/main/java/com/dypcet/journex/eventlog/EventType.java"
)


def test_vocabulary_is_exactly_the_frozen_list():
    assert [e.value for e in EventType] == FROZEN_VOCABULARY


def test_member_names_match_their_values():
    assert all(e.name == e.value.upper() for e in EventType)


def test_java_enum_has_the_same_vocabulary():
    body = JAVA_ENUM.read_text(encoding="utf-8").split("enum EventType", 1)[1]
    constants = re.findall(r"^\s+([A-Z][A-Z_]+)[,;]", body, flags=re.MULTILINE)

    assert [c.lower() for c in constants] == FROZEN_VOCABULARY
