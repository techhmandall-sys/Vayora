package com.dypcet.vayora.booking;

import static com.dypcet.vayora.booking.BookingStatus.CANCELLED;
import static com.dypcet.vayora.booking.BookingStatus.COMPLETED;
import static com.dypcet.vayora.booking.BookingStatus.CONFIRMED;
import static com.dypcet.vayora.booking.BookingStatus.NO_SHOW;
import static com.dypcet.vayora.booking.BookingStatus.PENDING;
import static com.dypcet.vayora.booking.BookingStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BookingStatusTest {

    @Test
    void pendingCanBeDecidedOrCancelled() {
        assertThat(PENDING.canTransitionTo(CONFIRMED)).isTrue();
        assertThat(PENDING.canTransitionTo(REJECTED)).isTrue();
        assertThat(PENDING.canTransitionTo(CANCELLED)).isTrue();
    }

    @Test
    void pendingCannotSkipStraightToATripOutcome() {
        assertThat(PENDING.canTransitionTo(COMPLETED)).isFalse();
        assertThat(PENDING.canTransitionTo(NO_SHOW)).isFalse();
    }

    @Test
    void confirmedCanBeCancelledOrEndAsATripOutcome() {
        assertThat(CONFIRMED.canTransitionTo(CANCELLED)).isTrue();
        assertThat(CONFIRMED.canTransitionTo(COMPLETED)).isTrue();
        assertThat(CONFIRMED.canTransitionTo(NO_SHOW)).isTrue();
        assertThat(CONFIRMED.canTransitionTo(REJECTED)).isFalse();
        assertThat(CONFIRMED.canTransitionTo(PENDING)).isFalse();
    }

    @Test
    void everyOtherStatusIsFinal() {
        for (BookingStatus end : List.of(REJECTED, CANCELLED, COMPLETED, NO_SHOW)) {
            for (BookingStatus next : BookingStatus.values()) {
                assertThat(end.canTransitionTo(next)).isFalse();
            }
        }
    }

    @Test
    void noStatusTransitionsToItself() {
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }

    @Test
    void rejectedCancelledAndNoShowBookingsGiveTheirSeatsBack() {
        assertThat(PENDING.holdsSeats()).isTrue();
        assertThat(CONFIRMED.holdsSeats()).isTrue();
        assertThat(COMPLETED.holdsSeats()).isTrue();
        assertThat(NO_SHOW.holdsSeats()).isFalse();
        assertThat(REJECTED.holdsSeats()).isFalse();
        assertThat(CANCELLED.holdsSeats()).isFalse();
    }
}
