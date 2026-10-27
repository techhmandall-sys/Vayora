from enum import StrEnum


class EventType(StrEnum):
    """The fixed event vocabulary. Do not extend this list.

    Mirrors com.dypcet.journex.eventlog.EventType; a test fails if they drift.
    """

    SEARCH_ISSUED = "search_issued"
    CANDIDATES_RETURNED = "candidates_returned"
    CANDIDATE_RANKED = "candidate_ranked"
    BOOKING_REQUESTED = "booking_requested"
    BOOKING_ACCEPTED = "booking_accepted"
    BOOKING_REJECTED = "booking_rejected"
    HAZARD_SCORED = "hazard_scored"
    TIER_SELECTED = "tier_selected"
    DRIVER_TIER_SELECTED = "driver_tier_selected"
    INTERVENTION_ISSUED = "intervention_issued"
    INTERVENTION_RESPONDED = "intervention_responded"
    BOOKING_CANCELLED = "booking_cancelled"
    SEAT_REFILLED = "seat_refilled"
    RECOVERY_SEARCH = "recovery_search"
    RECOVERY_BOOKED = "recovery_booked"
    SUPPLY_GAP_DETECTED = "supply_gap_detected"
    NUDGE_ISSUED = "nudge_issued"
    NUDGE_DISMISSED = "nudge_dismissed"
    NUDGE_CONVERTED = "nudge_converted"
    TRIP_STARTED = "trip_started"
    TRIP_COMPLETED = "trip_completed"
    TRIP_FAILED = "trip_failed"
    LAST_MILE_SHOWN = "last_mile_shown"
    LAST_MILE_OPTION_SELECTED = "last_mile_option_selected"
