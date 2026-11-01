package com.dypcet.vayora.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.booking.BookingService;
import com.dypcet.vayora.booking.BookingService.Booking;
import com.dypcet.vayora.booking.TripService;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.payment.PaymentService.Payment;
import com.dypcet.vayora.payment.PaymentService.Settlement;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.vehicle.VehicleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Money through a booking's whole life, against a real database and the mock provider.
 * One seat Kolhapur to Pune costs 520.00; the 10% fee is 52.00; the hold is 572.00.
 */
@AutoConfigureMockMvc
class PaymentFlowIntegrationTest extends PostgresIntegrationTest {

    private static final List<StopInput> STOPS = List.of(
            new StopInput("Kolhapur", 16.7050, 74.2433, 0),
            new StopInput("Pune", 18.5204, 73.8567, 270));

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private BookingService bookings;

    @Autowired
    private TripService trips;

    @Autowired
    private PaymentService payments;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private long driverId;
    private long passengerId;

    @BeforeEach
    void setUp() {
        driverId = user(UserRole.DRIVER);
        passengerId = user(UserRole.PASSENGER);
    }

    @Test
    void requestingABookingHoldsTheFarePlusTheFee() {
        Booking booking = book(ride(Duration.ofDays(3), true));

        assertThat(booking.price()).isEqualByComparingTo("520.00");
        assertThat(booking.serviceFee()).isEqualByComparingTo("52.00");
        assertThat(booking.total()).isEqualByComparingTo("572.00");
        assertThat(booking.refunded()).isNull();
        assertThat(ledger(booking)).containsExactly("HOLD 572.00");
        assertThat(intent(booking)).containsEntry("status", "HELD").containsEntry("provider", "MOCK")
                .containsEntry("method", "CARD");
    }

    @Test
    void aDeclinedPaymentLeavesNoBookingNoSeatTakenAndNoMoneyHeld() {
        long rideId = ride(Duration.ofDays(3), true);

        assertThatThrownBy(() -> bookings.request(passengerId, rideId, 0, 1, 1,
                new PaymentChoice(PaymentChoice.Method.UPI, "DECLINE"), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));

        assertThat(bookings.forPassenger(passengerId)).isEmpty();
        assertThat(payments.forPassenger(passengerId)).isEmpty();
        assertThat(seatsAvailable(rideId)).isEqualTo(3);
    }

    @Test
    void aDriverDecliningReturnsEverythingIncludingTheFee() {
        Booking booking = book(ride(Duration.ofDays(3), false));

        bookings.reject(driverId, booking.id());

        assertThat(ledger(booking)).containsExactly("HOLD 572.00", "RELEASE 572.00");
        assertThat(refund(booking)).isEqualTo("572.00 NOT_ACCEPTED");
        assertThat(intent(booking)).containsEntry("status", "SETTLED");
        assertThat(payments.forDriver(driverId)).isEmpty();
    }

    @Test
    void aRequestTheDriverNeverAnsweredIsReturnedInFullWhenTheRideStarts() {
        long rideId = ride(Duration.ofMinutes(30), false);
        Booking booking = book(rideId);

        trips.start(driverId, rideId);

        assertThat(ledger(booking)).containsExactly("HOLD 572.00", "RELEASE 572.00");
        assertThat(refund(booking)).isEqualTo("572.00 NOT_ACCEPTED");
    }

    @Test
    void cancellingBeforeTheDriverAcceptedReturnsEverythingHoweverLate() {
        Booking booking = book(ride(Duration.ofHours(2), false));

        bookings.cancel(passengerId, booking.id());

        assertThat(ledger(booking)).containsExactly("HOLD 572.00", "RELEASE 572.00");
        assertThat(refund(booking)).isEqualTo("572.00 NOT_ACCEPTED");
    }

    @Test
    void cancellingAnAcceptedBookingEarlyReturnsTheFareAndKeepsOnlyTheFee() {
        Booking booking = book(ride(Duration.ofDays(3), true));

        Booking cancelled = bookings.cancel(passengerId, booking.id());

        assertThat(ledger(booking)).containsExactly("HOLD 572.00", "RELEASE 520.00", "CAPTURE 52.00");
        assertThat(refund(booking)).isEqualTo("520.00 PASSENGER_CANCELLED");
        assertThat(cancelled.refunded()).isEqualByComparingTo("520.00");
        assertThat(payments.forDriver(driverId)).isEmpty();
    }

    @Test
    void cancellingBetweenOneAndTwoDaysBeforeSplitsTheFareWithTheDriver() {
        Booking booking = book(ride(Duration.ofHours(30), true));

        bookings.cancel(passengerId, booking.id());

        assertThat(ledger(booking)).containsExactly(
                "HOLD 572.00", "RELEASE 260.00", "CAPTURE 312.00", "PAYOUT 260.00");
        assertThat(refund(booking)).isEqualTo("260.00 PASSENGER_CANCELLED");
        assertThat(payments.forDriver(driverId)).singleElement().satisfies(settlement -> {
            assertThat(settlement.amount()).isEqualByComparingTo("260.00");
            assertThat(settlement.reason()).isEqualTo("LATE_CANCELLATION");
        });
    }

    @Test
    void cancellingInTheLastDayReturnsNothingAndTheDriverGetsTheFare() {
        Booking booking = book(ride(Duration.ofHours(2), true));

        Booking cancelled = bookings.cancel(passengerId, booking.id());

        assertThat(ledger(booking)).containsExactly("HOLD 572.00", "CAPTURE 572.00", "PAYOUT 520.00");
        assertThat(cancelled.refunded()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM refunds WHERE booking_id = ?", Integer.class, booking.id()))
                .isZero();
    }

    @Test
    void aDriverWithdrawingTheRideReturnsEverythingHoweverLate() {
        long rideId = ride(Duration.ofHours(2), true);
        Booking booking = book(rideId);

        trips.cancel(driverId, rideId);

        assertThat(ledger(booking)).containsExactly("HOLD 572.00", "RELEASE 572.00");
        assertThat(refund(booking)).isEqualTo("572.00 DRIVER_CANCELLED");
        assertThat(payments.forDriver(driverId)).isEmpty();
    }

    @Test
    void aCompletedTripPaysTheDriverTheFareAndANoShowDoesTheSame() {
        long rideId = ride(Duration.ofMinutes(30), true);
        Booking travelled = book(rideId);
        long absentee = user(UserRole.PASSENGER);
        Booking absent = bookings.request(absentee, rideId, 0, 1, 1, PaymentChoice.card(), null);

        trips.start(driverId, rideId);
        assertThat(ledger(travelled)).containsExactly("HOLD 572.00"); // nothing moves until the end
        trips.noShow(driverId, absent.id());
        trips.complete(driverId, rideId);

        for (Booking booking : List.of(travelled, absent)) {
            assertThat(ledger(booking)).containsExactly("HOLD 572.00", "CAPTURE 572.00", "PAYOUT 520.00");
        }
        assertThat(payments.forDriver(driverId)).extracting(Settlement::reason)
                .containsExactlyInAnyOrder("TRIP_COMPLETED", "PASSENGER_NO_SHOW");
        assertThat(payments.forDriver(driverId)).extracting(Settlement::provider).containsOnly("MOCK");
    }

    @Test
    void everySettledPaymentBalancesAndNothingIsSettledTwice() {
        long rideId = ride(Duration.ofHours(30), true);
        Booking booking = book(rideId);
        bookings.cancel(passengerId, booking.id());

        // A second cancel, and the ride being withdrawn afterwards, must not move money again.
        assertThatThrownBy(() -> bookings.cancel(passengerId, booking.id()))
                .isInstanceOf(ResponseStatusException.class);
        trips.cancel(driverId, rideId);
        assertThat(ledger(booking)).hasSize(4);

        // Across every payment any test has settled: hold = capture + release, and the
        // driver is never paid more than was captured. A payment made after the ride held
        // what was owed then, which a late cancellation makes less than the whole amount.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_intents p
                JOIN (SELECT intent_id,
                             COALESCE(SUM(amount) FILTER (WHERE kind = 'HOLD'), 0) AS held,
                             COALESCE(SUM(amount) FILTER (WHERE kind = 'CAPTURE'), 0) AS captured,
                             COALESCE(SUM(amount) FILTER (WHERE kind = 'RELEASE'), 0) AS released,
                             COALESCE(SUM(amount) FILTER (WHERE kind = 'PAYOUT'), 0) AS paid_out
                      FROM ledger_entries GROUP BY intent_id) l ON l.intent_id = p.id
                WHERE p.status = 'SETTLED'
                  AND (l.held <> CASE WHEN p.deferred THEN p.amount_due ELSE p.amount END
                       OR l.held <> l.captured + l.released
                       OR l.paid_out > l.captured)""", Integer.class)).isZero();
    }

    @Test
    void theLedgerCannotBeRewritten() {
        Booking booking = book(ride(Duration.ofDays(3), true));

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE intent_id = "
                + "(SELECT id FROM payment_intents WHERE booking_id = ?)", booking.id()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE intent_id = "
                + "(SELECT id FROM payment_intents WHERE booking_id = ?)", booking.id()))
                .hasMessageContaining("append-only");
        assertThat(ledger(booking)).containsExactly("HOLD 572.00");
    }

    @Test
    void aRepeatedRequestWithTheSameKeyBooksAndChargesOnce() {
        long rideId = ride(Duration.ofDays(3), true);
        String key = UUID.randomUUID().toString();

        Booking first = bookings.request(passengerId, rideId, 0, 1, 1, PaymentChoice.card(), key);
        Booking again = bookings.request(passengerId, rideId, 0, 1, 1, PaymentChoice.card(), key);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(seatsAvailable(rideId)).isEqualTo(2);
        assertThat(ledger(first)).containsExactly("HOLD 572.00");
        assertThat(bookings.forPassenger(passengerId)).hasSize(1);

        // The same key for something else is a mistake, not a repeat.
        assertThatThrownBy(() -> bookings.request(passengerId, rideId, 0, 1, 2, PaymentChoice.card(), key))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        // Another passenger may use the same key.
        Booking other = bookings.request(user(UserRole.PASSENGER), rideId, 0, 1, 1,
                PaymentChoice.card(), key);
        assertThat(other.id()).isNotEqualTo(first.id());
    }

    @Test
    void aPassengerSeesOnlyTheirOwnPaymentsWithWhatCameBack() {
        Booking booking = book(ride(Duration.ofHours(30), true));
        bookings.cancel(passengerId, booking.id());

        assertThat(payments.forPassenger(passengerId)).singleElement().satisfies((Payment payment) -> {
            assertThat(payment.bookingId()).isEqualTo(booking.id());
            assertThat(payment.amount()).isEqualByComparingTo("572.00");
            assertThat(payment.refunded()).isEqualByComparingTo("260.00");
            assertThat(payment.refundReason()).isEqualTo("PASSENGER_CANCELLED");
            assertThat(payment.status()).isEqualTo("SETTLED");
            assertThat(payment.provider()).isEqualTo("MOCK");
            assertThat(payment.fromStop()).isEqualTo("Kolhapur");
        });
        assertThat(payments.forPassenger(user(UserRole.PASSENGER))).isEmpty();
        assertThat(payments.forDriver(user(UserRole.DRIVER))).isEmpty();
    }

    @Test
    void theHttpApiRequiresAPaymentMethodReportsADeclineAndHonoursTheKey() throws Exception {
        long rideId = ride(Duration.ofDays(3), true);
        String passenger = "Bearer " + tokens.issue(passengerId, UserRole.PASSENGER);
        Map<String, Object> body = Map.of("rideId", rideId, "fromSeq", 0, "toSeq", 1, "seats", 1);

        mvc.perform(post("/api/v1/bookings").header("Authorization", passenger)
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        Map<String, Object> declined = new java.util.HashMap<>(body);
        declined.put("paymentMethod", "CARD");
        declined.put("mockOutcome", "DECLINE");
        mvc.perform(post("/api/v1/bookings").header("Authorization", passenger)
                        .contentType("application/json").content(json.writeValueAsString(declined)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value("The payment was declined. Nothing was booked."));

        Map<String, Object> paid = new java.util.HashMap<>(body);
        paid.put("paymentMethod", "WALLET");
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(post("/api/v1/bookings").header("Authorization", passenger)
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(json.writeValueAsString(paid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(572.0))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/bookings").header("Authorization", passenger)
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(json.writeValueAsString(paid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(json.readTree(first).get("id").asLong()));
        assertThat(seatsAvailable(rideId)).isEqualTo(2);

        mvc.perform(post("/api/v1/bookings").header("Authorization", passenger)
                        .header("Idempotency-Key", "x".repeat(101))
                        .contentType("application/json").content(json.writeValueAsString(paid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theTermsShownToPassengersAreTheOnesTheServerApplies() throws Exception {
        String passenger = "Bearer " + tokens.issue(passengerId, UserRole.PASSENGER);

        mvc.perform(get("/api/v1/payment-terms").header("Authorization", passenger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("MOCK"))
                .andExpect(jsonPath("$.serviceFeeRate").value(0.10))
                .andExpect(jsonPath("$.refundTiers.length()").value(2))
                .andExpect(jsonPath("$.refundTiers[0].atLeastHoursBefore").value(48))
                .andExpect(jsonPath("$.refundTiers[0].fareShare").value(1.0))
                .andExpect(jsonPath("$.refundTiers[1].atLeastHoursBefore").value(24))
                .andExpect(jsonPath("$.refundTiers[1].fareShare").value(0.5));
        mvc.perform(get("/api/v1/payment-terms")).andExpect(status().isUnauthorized());
    }

    private Booking book(long rideId) {
        return bookings.request(passengerId, rideId, 0, 1, 1, PaymentChoice.card(), null);
    }

    private List<String> ledger(Booking booking) {
        return jdbc.queryForList("""
                SELECT l.kind || ' ' || l.amount FROM ledger_entries l
                JOIN payment_intents p ON p.id = l.intent_id
                WHERE p.booking_id = ? ORDER BY l.id""", String.class, booking.id());
    }

    private Map<String, Object> intent(Booking booking) {
        return jdbc.queryForMap(
                "SELECT status, provider, method FROM payment_intents WHERE booking_id = ?",
                booking.id());
    }

    private String refund(Booking booking) {
        return jdbc.queryForObject(
                "SELECT amount || ' ' || reason FROM refunds WHERE booking_id = ?", String.class,
                booking.id());
    }

    private int seatsAvailable(long rideId) {
        return jdbc.queryForObject(
                "SELECT seats_available FROM ride_segments WHERE ride_id = ?", Integer.class, rideId);
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test " + role, role).id();
    }

    private long ride(Duration untilDeparture, boolean instantBooking) {
        long vehicleId = vehicles.create(driverId, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        return rides.publish(driverId, new PublishRide(vehicleId, Instant.now().plus(untilDeparture),
                3, instantBooking, STOPS, List.of(new BigDecimal("520.00")))).id();
    }
}
