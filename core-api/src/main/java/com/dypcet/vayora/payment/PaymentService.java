package com.dypcet.vayora.payment;

import com.dypcet.vayora.pass.PassService;
import com.dypcet.vayora.refund.RefundPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Holds a passenger's money when a booking is requested and settles it, exactly once,
 * when the booking ends.
 *
 * <p>Every method here is called from inside the booking's own transaction, so money and
 * booking state commit or roll back together. None opens a transaction of its own.
 *
 * <p>A booking with no intent (made before payments existed) is left alone.
 */
@Service
public class PaymentService {

    public enum RefundReason {
        NOT_ACCEPTED,
        DRIVER_CANCELLED,
        PASSENGER_CANCELLED
    }

    public enum SettlementReason {
        TRIP_COMPLETED,
        PASSENGER_NO_SHOW,
        LATE_CANCELLATION
    }

    /** One of a passenger's payments, with what came back. */
    public record Payment(long bookingId, String fromStop, String toStop, BigDecimal fare,
            BigDecimal serviceFee, BigDecimal amount, String method, String provider,
            String status, BigDecimal refunded, String refundReason, Instant createdAt) {
    }

    /** One payout to a driver. */
    public record Settlement(long bookingId, String passengerName, String fromStop, String toStop,
            BigDecimal amount, String reason, String provider, Instant createdAt) {
    }

    private record Intent(long id, BigDecimal fare, BigDecimal serviceFee, BigDecimal amount,
            String providerRef, String status) {
    }

    private final JdbcTemplate jdbc;
    private final PaymentProvider provider;
    private final ServiceFee serviceFee;
    private final PassService passes;
    private final Clock clock;
    private final RefundPolicy refundPolicy;
    private final KeptRides keptRides;

    public PaymentService(JdbcTemplate jdbc, PaymentProvider provider, ServiceFee serviceFee,
            RefundPolicy refundPolicy,
            PassService passes, Clock clock, KeptRides keptRides) {
        this.keptRides = keptRides;
        this.passes = passes;
        this.clock = clock;
        this.jdbc = jdbc;
        this.provider = provider;
        this.serviceFee = serviceFee;
        this.refundPolicy = refundPolicy;
    }

    /**
     * Reserves the fare plus the service fee, or the fare alone if a pass of the passenger's
     * is running. The fee is the lower one if the ride's driver keeps their rides. If the
     * provider declines, throws 402 and the caller's transaction rolls back, so no booking
     * and no seat is left behind.
     */
    public void hold(long bookingId, long passengerId, long driverId, BigDecimal fare, PaymentChoice choice) {
        BigDecimal fee = feeFor(passengerId, driverId, fare);
        BigDecimal amount = fare.add(fee);
        if (amount.signum() == 0) {
            return; // a free ride: there is nothing to hold
        }
        PaymentProvider.Hold hold = provider.hold(amount, choice).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "The payment was declined. Nothing was booked."));
        Long intentId = jdbc.queryForObject("""
                INSERT INTO payment_intents (booking_id, passenger_id, fare, service_fee, amount,
                                             method, provider, provider_ref, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'HELD') RETURNING id""",
                Long.class, bookingId, passengerId, fare, fee, amount, hold.method().name(),
                provider.name(), hold.reference());
        entry(intentId, "HOLD", amount);
    }

    /**
     * What booking these seats would take now: the fare for the stretch and this passenger's
     * fee, as {@link #hold} works it out. What a checkout is opened for.
     */
    public BigDecimal amountDue(long passengerId, long rideId, int fromSeq, int toSeq, int seats) {
        List<Object[]> found = jdbc.query("""
                SELECT r.driver_id, SUM(s.price) * ? AS fare, COUNT(*) AS legs
                FROM rides r JOIN ride_segments s ON s.ride_id = r.id AND s.seq >= ? AND s.seq < ?
                WHERE r.id = ? AND r.status = 'SCHEDULED'
                GROUP BY r.driver_id""",
                (rs, n) -> new Object[] {rs.getLong("driver_id"), rs.getBigDecimal("fare"), rs.getInt("legs")},
                seats, fromSeq, toSeq, rideId);
        if (found.isEmpty() || toSeq <= fromSeq || (int) found.get(0)[2] != toSeq - fromSeq) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No ride open for booking has those stops");
        }
        BigDecimal fare = (BigDecimal) found.get(0)[1];
        return fare.add(feeFor(passengerId, (long) found.get(0)[0], fare));
    }

    /** Whether the passenger must be there to pay: then nothing can be booked in their absence. */
    public boolean needsThePassengerToPay() {
        return provider.needsThePassengerToPay();
    }

    /**
     * The service fee this passenger would pay on this fare, on this driver's ride, now:
     * none while a pass runs, the lower one if the driver keeps their rides.
     */
    public BigDecimal feeFor(long passengerId, long driverId, BigDecimal fare) {
        return passes.covers(passengerId, clock.instant()) ? BigDecimal.ZERO.setScale(2)
                : serviceFee.on(fare, keptRides.by(driverId));
    }

    /** Returns everything, fee included: the driver declined, never answered, or withdrew. */
    public void releaseInFull(long bookingId, RefundReason reason) {
        lockHeld(bookingId).ifPresent(intent ->
                settle(bookingId, intent, intent.amount(), reason, null, null));
    }

    /**
     * Settles a passenger's own cancellation. Before the driver accepted, everything comes
     * back. After, the fare comes back by the timing tiers, the service fee is kept, and
     * the part of the fare not refunded goes to the driver.
     */
    public void settlePassengerCancellation(long bookingId, long driverId, boolean accepted,
            Instant boarding, Instant now) {
        lockHeld(bookingId).ifPresent(intent -> {
            BigDecimal refund = accepted
                    ? refundPolicy.fareRefund(intent.fare(), Duration.between(now, boarding))
                    : intent.amount();
            RefundReason reason = accepted ? RefundReason.PASSENGER_CANCELLED : RefundReason.NOT_ACCEPTED;
            settle(bookingId, intent, refund, reason, driverId, SettlementReason.LATE_CANCELLATION);
        });
    }

    /** Keeps everything and pays the fare to the driver: the trip ran, or the passenger did not show. */
    public void captureInFull(long bookingId, long driverId, SettlementReason reason) {
        lockHeld(bookingId).ifPresent(intent ->
                settle(bookingId, intent, BigDecimal.ZERO, null, driverId, reason));
    }

    public List<Payment> forPassenger(long passengerId) {
        return jdbc.query("""
                SELECT p.booking_id, s.name AS from_stop, t.name AS to_stop, p.fare, p.service_fee,
                       p.amount, p.method, p.provider, p.status, p.created_at,
                       COALESCE(f.amount, 0) AS refunded, f.reason AS refund_reason
                FROM payment_intents p
                JOIN bookings b ON b.id = p.booking_id
                JOIN ride_stops s ON s.ride_id = b.ride_id AND s.seq = b.from_seq
                JOIN ride_stops t ON t.ride_id = b.ride_id AND t.seq = b.to_seq
                LEFT JOIN refunds f ON f.booking_id = p.booking_id
                WHERE p.passenger_id = ? ORDER BY p.id DESC""",
                (rs, n) -> new Payment(rs.getLong("booking_id"), rs.getString("from_stop"),
                        rs.getString("to_stop"), rs.getBigDecimal("fare"),
                        rs.getBigDecimal("service_fee"), rs.getBigDecimal("amount"),
                        rs.getString("method"), rs.getString("provider"), rs.getString("status"),
                        rs.getBigDecimal("refunded"), rs.getString("refund_reason"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                passengerId);
    }

    public List<Settlement> forDriver(long driverId) {
        return jdbc.query("""
                SELECT x.booking_id, u.full_name, s.name AS from_stop, t.name AS to_stop,
                       x.amount, x.reason, x.provider, x.created_at
                FROM settlements x
                JOIN bookings b ON b.id = x.booking_id
                JOIN users u ON u.id = b.passenger_id
                JOIN ride_stops s ON s.ride_id = b.ride_id AND s.seq = b.from_seq
                JOIN ride_stops t ON t.ride_id = b.ride_id AND t.seq = b.to_seq
                WHERE x.driver_id = ? ORDER BY x.id DESC""",
                (rs, n) -> new Settlement(rs.getLong("booking_id"), rs.getString("full_name"),
                        rs.getString("from_stop"), rs.getString("to_stop"),
                        rs.getBigDecimal("amount"), rs.getString("reason"),
                        rs.getString("provider"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                driverId);
    }

    /**
     * Splits the hold into what goes back to the passenger and what is kept, and pays the
     * driver their part of what is kept. The platform keeps the service fee out of any
     * capture; the driver gets the rest.
     */
    private void settle(long bookingId, Intent intent, BigDecimal refund, RefundReason refundReason,
            Long driverId, SettlementReason settlementReason) {
        BigDecimal captured = intent.amount().subtract(refund);
        if (refund.signum() > 0) {
            provider.release(intent.providerRef(), refund);
            entry(intent.id(), "RELEASE", refund);
            jdbc.update("""
                    INSERT INTO refunds (booking_id, intent_id, amount, reason)
                    VALUES (?, ?, ?, ?)""",
                    bookingId, intent.id(), refund, refundReason.name());
        }
        if (captured.signum() > 0) {
            provider.capture(intent.providerRef(), captured);
            entry(intent.id(), "CAPTURE", captured);
            BigDecimal toDriver = captured.subtract(intent.serviceFee());
            if (toDriver.signum() > 0) {
                String payoutReference = provider.payout(driverId, toDriver);
                entry(intent.id(), "PAYOUT", toDriver);
                jdbc.update("""
                        INSERT INTO settlements (booking_id, driver_id, amount, reason, provider,
                                                 provider_ref)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                        bookingId, driverId, toDriver, settlementReason.name(), provider.name(),
                        payoutReference);
            }
        }
        jdbc.update("UPDATE payment_intents SET status = 'SETTLED', settled_at = now() WHERE id = ?",
                intent.id());
    }

    /**
     * Locks the booking's intent. Empty if the booking has none. An intent already
     * settled is a bug in the caller: a booking ends once.
     */
    private Optional<Intent> lockHeld(long bookingId) {
        List<Intent> found = jdbc.query("""
                SELECT id, fare, service_fee, amount, provider_ref, status
                FROM payment_intents WHERE booking_id = ? FOR UPDATE""",
                (rs, n) -> new Intent(rs.getLong("id"), rs.getBigDecimal("fare"),
                        rs.getBigDecimal("service_fee"), rs.getBigDecimal("amount"),
                        rs.getString("provider_ref"), rs.getString("status")),
                bookingId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!"HELD".equals(found.get(0).status())) {
            throw new IllegalStateException("Payment for booking " + bookingId + " is already settled");
        }
        return found.stream().findFirst();
    }

    private void entry(long intentId, String kind, BigDecimal amount) {
        jdbc.update("INSERT INTO ledger_entries (intent_id, kind, amount) VALUES (?, ?, ?)",
                intentId, kind, amount);
    }
}
