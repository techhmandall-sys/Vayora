package com.dypcet.vayora.booking;

/**
 * Where a booking is in its life. Only PENDING and CONFIRMED can still change; every
 * other status is final.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    CANCELLED,
    COMPLETED,
    NO_SHOW;

    public boolean canTransitionTo(BookingStatus next) {
        return switch (this) {
            case PENDING -> next == CONFIRMED || next == REJECTED || next == CANCELLED;
            case CONFIRMED -> next == CANCELLED || next == COMPLETED || next == NO_SHOW;
            case REJECTED, CANCELLED, COMPLETED, NO_SHOW -> false;
        };
    }

    /**
     * Whether the booking's seats count against the ride's inventory. REJECTED, CANCELLED
     * and NO_SHOW give them back: a seat nobody boarded can be sold again from a later
     * stop while the ride is under way.
     */
    public boolean holdsSeats() {
        return this != REJECTED && this != CANCELLED && this != NO_SHOW;
    }
}
