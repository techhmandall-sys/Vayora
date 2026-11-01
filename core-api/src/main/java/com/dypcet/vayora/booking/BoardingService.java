package com.dypcet.vayora.booking;

import java.security.SecureRandom;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A booking's reference and its boarding code.
 *
 * <p>The passenger shows the code at pickup; the driver checks it against the booking.
 * The code is never sent to the driver, so the check means something. It is only a check:
 * it changes nothing about the booking, and a wrong code stops nobody from travelling.
 */
@Service
public class BoardingService {

    /** @param reference a short name for the booking, for speaking or writing down */
    public record BoardingPass(long bookingId, String reference, String code) {
    }

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public BoardingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Four digits, for a new booking. */
    String newCode() {
        return String.format("%04d", random.nextInt(10_000));
    }

    public static String reference(long bookingId) {
        return String.format("JX-%06d", bookingId);
    }

    /** The passenger's own pass, while the booking is one they could travel on. */
    public BoardingPass pass(long passengerId, long bookingId) {
        List<String> found = jdbc.queryForList("""
                SELECT boarding_code FROM bookings
                WHERE id = ? AND passenger_id = ? AND status IN ('PENDING', 'CONFIRMED')""",
                String.class, bookingId, passengerId);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No boarding pass for that booking");
        }
        return new BoardingPass(bookingId, reference(bookingId), found.get(0));
    }

    /** Whether the code a passenger showed is the one on this booking of the driver's ride. */
    public boolean check(long driverId, long bookingId, String code) {
        List<String> found = jdbc.queryForList("""
                SELECT b.boarding_code FROM bookings b JOIN rides r ON r.id = b.ride_id
                WHERE b.id = ? AND r.driver_id = ?""",
                String.class, bookingId, driverId);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        }
        return found.get(0) != null && found.get(0).equals(code);
    }
}
