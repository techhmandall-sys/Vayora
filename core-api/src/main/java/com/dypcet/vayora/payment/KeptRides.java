package com.dypcet.vayora.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether a driver keeps their rides, which earns their passengers a lower service fee
 * (feature 94): enough rides completed, and none withdrawn among those due to leave within
 * the window or later. From what the driver has done alone, never from a prediction.
 */
@Component
public class KeptRides {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int minCompleted;
    private final Duration window;

    public KeptRides(JdbcTemplate jdbc, Clock clock,
            @Value("${vayora.payment.kept-rides.min-completed}") int minCompleted,
            @Value("${vayora.payment.kept-rides.window}") Duration window) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.minCompleted = minCompleted;
        this.window = window;
    }

    /** The rule itself, for a caller that has already counted. */
    public boolean qualifies(int ridesCompleted, int ridesWithdrawnInWindow) {
        return ridesCompleted >= minCompleted && ridesWithdrawnInWindow == 0;
    }

    /** Withdrawn rides due to leave at or after this count against a driver. */
    public Instant windowStart() {
        return clock.instant().minus(window);
    }

    public boolean by(long driverId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED' AND departure_time >= ?) AS withdrawn
                FROM rides WHERE driver_id = ?""",
                (rs, n) -> qualifies(rs.getInt("completed"), rs.getInt("withdrawn")),
                OffsetDateTime.ofInstant(windowStart(), ZoneOffset.UTC), driverId));
    }
}
