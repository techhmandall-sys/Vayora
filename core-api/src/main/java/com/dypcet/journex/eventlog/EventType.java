package com.dypcet.journex.eventlog;

import java.util.Locale;

/**
 * The fixed event vocabulary. Do not extend this list.
 *
 * <p>Mirrors {@code journex_events.vocabulary.EventType}; a test fails if they drift.
 */
public enum EventType {
    SEARCH_ISSUED,
    CANDIDATES_RETURNED,
    CANDIDATE_RANKED,
    BOOKING_REQUESTED,
    BOOKING_ACCEPTED,
    BOOKING_REJECTED,
    HAZARD_SCORED,
    TIER_SELECTED,
    DRIVER_TIER_SELECTED,
    INTERVENTION_ISSUED,
    INTERVENTION_RESPONDED,
    BOOKING_CANCELLED,
    SEAT_REFILLED,
    RECOVERY_SEARCH,
    RECOVERY_BOOKED,
    SUPPLY_GAP_DETECTED,
    NUDGE_ISSUED,
    NUDGE_DISMISSED,
    NUDGE_CONVERTED,
    TRIP_STARTED,
    TRIP_COMPLETED,
    TRIP_FAILED,
    LAST_MILE_SHOWN,
    LAST_MILE_OPTION_SELECTED;

    /** The value stored in {@code events.event_type}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
