package com.dypcet.vayora.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes booking requests no driver answered in time, so their seats and the passenger's
 * money are not tied up until the ride leaves.
 */
@Component
public class BookingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryJob.class);

    private final BookingService bookings;

    public BookingExpiryJob(BookingService bookings) {
        this.bookings = bookings;
    }

    @Scheduled(cron = "${vayora.booking.expiry-cron}", zone = "Asia/Kolkata")
    void scheduledRun() {
        int closed = closeUnanswered();
        if (closed > 0) {
            log.info("Closed {} unanswered booking requests", closed);
        }
    }

    /**
     * Each request is closed in a transaction of its own: one that cannot be closed must
     * not keep the others open.
     *
     * @return how many were closed
     */
    public int closeUnanswered() {
        int closed = 0;
        for (long bookingId : bookings.unanswered()) {
            try {
                closed += bookings.expire(bookingId) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Request {} could not be closed: {}", bookingId, e.getMessage());
            }
        }
        return closed;
    }
}
