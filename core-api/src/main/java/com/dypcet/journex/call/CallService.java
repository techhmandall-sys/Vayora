package com.dypcet.journex.call;

import com.dypcet.journex.notification.NotificationService;
import com.dypcet.journex.notification.NotificationService.Type;
import com.dypcet.journex.notification.Text;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Masked calling between a passenger and their driver (feature 61). With a Twilio account
 * configured, a real call is bridged through the platform's number, so neither side sees
 * the other's; without one it is simulated, nothing is dialled, and the answer says so. No
 * number is returned to anyone, then or ever. Who may call whom and when is the same either way.
 */
@Service
public class CallService {

    /** What the caller is told. It names the other side and carries no phone number, by design. */
    public record Relay(String provider, String calleeName) {
    }

    private record Parties(long passengerId, String passengerName, String passengerPhone,
            long driverId, String driverName, String driverPhone, String status, Instant boarding) {
    }

    private static final Logger log = LoggerFactory.getLogger(CallService.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final TwilioVoice voice;
    private final Clock clock;
    private final Duration opensBefore;

    public CallService(JdbcTemplate jdbc, NotificationService notifications, TwilioVoice voice, Clock clock,
            @Value("${journex.calling.opens-before}") Duration opensBefore) {
        this.jdbc = jdbc;
        this.voice = voice;
        this.notifications = notifications;
        this.clock = clock;
        this.opensBefore = opensBefore;
    }

    /**
     * The passenger calls the driver of their booking, or the driver calls that passenger:
     * only while the booking is confirmed, from shortly before boarding, and only between
     * confirmed phone numbers, as a real relay would need.
     */
    @Transactional
    public Relay call(long callerId, long bookingId) {
        List<Parties> found = jdbc.query("""
                SELECT b.passenger_id, p.full_name AS passenger_name,
                       CASE WHEN p.phone_verified_at IS NOT NULL THEN p.phone END AS passenger_phone,
                       r.driver_id, d.full_name AS driver_name,
                       CASE WHEN d.phone_verified_at IS NOT NULL THEN d.phone END AS driver_phone, b.status,
                       r.departure_time + make_interval(mins => s.offset_minutes) AS boarding
                FROM bookings b
                JOIN rides r ON r.id = b.ride_id
                JOIN users p ON p.id = b.passenger_id
                JOIN users d ON d.id = r.driver_id
                JOIN ride_stops s ON s.ride_id = r.id AND s.seq = b.from_seq
                WHERE b.id = ?""",
                (rs, n) -> new Parties(rs.getLong("passenger_id"), rs.getString("passenger_name"),
                        rs.getString("passenger_phone"), rs.getLong("driver_id"),
                        rs.getString("driver_name"), rs.getString("driver_phone"),
                        rs.getString("status"), rs.getObject("boarding", OffsetDateTime.class).toInstant()),
                bookingId);
        // Someone else's booking looks the same as one that does not exist.
        if (found.isEmpty() || (found.get(0).passengerId() != callerId && found.get(0).driverId() != callerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        }
        Parties parties = found.get(0);
        boolean passengerCalls = parties.passengerId() == callerId;
        if (!"CONFIRMED".equals(parties.status())) {
            throw conflict("A call can only be made while the booking is confirmed");
        }
        if (clock.instant().isBefore(parties.boarding().minus(opensBefore))) {
            throw conflict("It is too early to call: calls open shortly before the trip");
        }
        String callerPhone = passengerCalls ? parties.passengerPhone() : parties.driverPhone();
        String calleePhone = passengerCalls ? parties.driverPhone() : parties.passengerPhone();
        if (callerPhone == null) {
            throw conflict("Confirm your phone number on your account page before calling");
        }
        if (calleePhone == null) {
            throw conflict("They have no confirmed phone number. Use the chat instead.");
        }
        String callerName = passengerCalls ? parties.passengerName() : parties.driverName();
        String calleeName = passengerCalls ? parties.driverName() : parties.passengerName();
        long calleeId = passengerCalls ? parties.driverId() : parties.passengerId();
        if (voice.configured()) {
            try {
                voice.bridge(callerPhone, calleePhone);
            } catch (RestClientException e) {
                log.warn("Call on booking {} not placed: {}", bookingId, e.getMessage());
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The call could not be placed. Try again, or use the ride's chat.");
            }
            notifications.send(calleeId, Type.CALL_REQUESTED, Text.of("{name} is calling you through VAYORA, "
                    + "from the platform's number. Neither of you sees the other's number.", "name", callerName), bookingId);
            return new Relay(TwilioVoice.NAME, calleeName);
        }
        notifications.send(calleeId, Type.CALL_REQUESTED,
                Text.of("{name} tried to call you through VAYORA. Calls are simulated, so no call came through. "
                        + "Reply in the ride's chat.", "name", callerName),
                bookingId);
        return new Relay("mock", calleeName);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
