package com.dypcet.vayora.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.booking.BookingService;
import com.dypcet.vayora.booking.BookingService.Booking;
import com.dypcet.vayora.booking.BookingStatus;
import com.dypcet.vayora.booking.FlexTier;
import com.dypcet.vayora.booking.TripService;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.payment.PaymentService.Settlement;
import com.dypcet.vayora.receipt.ReceiptService;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.vehicle.VehicleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Paying after the ride, against a real database and the mock provider. A booking takes
 * nothing, then owes exactly what one paid up front would have kept: 572.00 for one seat
 * Kolhapur to Pune (520.00 fare and the 52.00 fee) when the trip runs.
 */
@AutoConfigureMockMvc
class PayAfterRideIntegrationTest extends PostgresIntegrationTest {

    private static final List<StopInput> STOPS = List.of(
            new StopInput("Kolhapur", 16.7050, 74.2433, 0),
            new StopInput("Pune", 18.5204, 73.8567, 270));
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

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
    private ReceiptService receipts;

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
    void bookingToPayAfterTheRideTakesNothingAndSaysWhatItWillCost() {
        long rideId = ride(Duration.ofDays(3), true, true);

        Booking booking = later(passengerId, rideId);

        assertThat(booking.payAfterRide()).isTrue();
        assertThat(booking.paymentStatus()).isEqualTo("DEFERRED");
        assertThat(booking.total()).isEqualByComparingTo("572.00");
        assertThat(booking.amountDue()).isNull();
        assertThat(ledger(booking)).isEmpty();
        assertThat(intent(booking)).containsEntry("method", null).containsEntry("provider", null);
        assertThat(rides.get(rideId).payAfterRide()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'pay_after_ride' FROM events
                WHERE run_id IS NULL AND event_type = 'booking_requested' AND payload->>'booking_id' = ?""",
                String.class, String.valueOf(booking.id()))).isEqualTo("true");
    }

    @Test
    void aDriverWhoTakesPaymentWhenBookingIsNotPaidAfterTheRide() {
        long rideId = ride(Duration.ofDays(3), true, false);

        assertThatThrownBy(() -> later(passengerId, rideId)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getReason()).isEqualTo("This driver takes payment when you book, not after the ride"));

        assertThat(bookings.forPassenger(passengerId)).isEmpty();
        assertThat(seatsAvailable(rideId)).isEqualTo(3);
    }

    @Test
    void aCompletedTripOwesEverythingAndNothingElseIsBookedUntilItIsPaid() {
        long rideId = ride(Duration.ofMinutes(30), true, true);
        Booking booking = later(passengerId, rideId);
        trips.start(driverId, rideId);
        trips.complete(driverId, rideId);

        Booking ended = bookings.forPassenger(passengerId).get(0);
        assertThat(ended.paymentStatus()).isEqualTo("DUE");
        assertThat(ended.amountDue()).isEqualByComparingTo("572.00");
        assertThat(intent(booking)).containsEntry("due_reason", "TRIP_COMPLETED");
        assertThat(payments.forDriver(driverId)).isEmpty();
        long next = ride(Duration.ofDays(2), true, false);
        assertThatThrownBy(() -> bookings.request(passengerId, next, 0, 1, 1, PaymentChoice.card(), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getReason()).isEqualTo(
                        "You have a payment due from an earlier booking. Pay it in My bookings before booking again."));

        Booking paid = bookings.payDue(passengerId, booking.id(), new PaymentChoice(PaymentChoice.Method.UPI, null));

        assertThat(paid.paymentStatus()).isEqualTo("SETTLED");
        assertThat(ledger(booking)).containsExactly("HOLD 572.00", "CAPTURE 572.00", "PAYOUT 520.00");
        assertThat(intent(booking)).containsEntry("method", "UPI").containsEntry("provider", "MOCK");
        assertThat(payments.forDriver(driverId)).singleElement().satisfies((Settlement settlement) -> {
            assertThat(settlement.amount()).isEqualByComparingTo("520.00");
            assertThat(settlement.reason()).isEqualTo("TRIP_COMPLETED");
        });
        assertThat(bookings.request(passengerId, next, 0, 1, 1, PaymentChoice.card(), null).status())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void theDriverCanRecordCashAndThenNothingIsPaidOutAndTheFeeIsTheirsToPassOn() {
        long rideId = ride(Duration.ofMinutes(30), true, true);
        Booking booking = later(passengerId, rideId);
        trips.start(driverId, rideId);
        trips.complete(driverId, rideId);

        assertThatThrownBy(() -> bookings.paidInCash(user(UserRole.DRIVER), booking.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        bookings.paidInCash(driverId, booking.id());

        assertThat(ledger(booking)).containsExactly("HOLD 572.00", "CAPTURE 572.00");
        assertThat(intent(booking)).containsEntry("status", "SETTLED").containsEntry("method", "CASH")
                .containsEntry("provider", "CASH");
        assertThat(payments.forDriver(driverId)).singleElement().satisfies((Settlement settlement) -> {
            assertThat(settlement.amount()).isEqualByComparingTo("520.00");
            assertThat(settlement.provider()).isEqualTo("CASH");
        });
        assertThat(payments.cashFeesHeld(driverId)).isEqualByComparingTo("52.00");
        // Paid is paid: neither side can settle it again.
        assertThatThrownBy(() -> bookings.paidInCash(driverId, booking.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("Nothing is due on this booking"));
        assertThatThrownBy(() -> bookings.payDue(passengerId, booking.id(), PaymentChoice.card()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(ledger(booking)).hasSize(2);
    }

    @Test
    void cancellingOwesWhatABookingPaidUpFrontWouldHaveKept() {
        // Accepted: the fee, plus the fare the timing tiers would not have returned.
        assertThat(cancelled(Duration.ofDays(3), true)).isEqualTo("DUE 52.00 PASSENGER_CANCELLED");
        assertThat(cancelled(Duration.ofHours(30), true)).isEqualTo("DUE 312.00 PASSENGER_CANCELLED");
        assertThat(cancelled(Duration.ofHours(2), true)).isEqualTo("DUE 572.00 PASSENGER_CANCELLED");
        // Never accepted: nothing, however late.
        assertThat(cancelled(Duration.ofHours(2), false)).isEqualTo("VOID");
    }

    @Test
    void aDriverDecliningOrWithdrawingLeavesNothingOwed() {
        Booking declined = later(passengerId, ride(Duration.ofDays(3), false, true));
        bookings.reject(driverId, declined.id());
        long withdrawn = ride(Duration.ofHours(2), true, true);
        Booking cancelled = later(passengerId, withdrawn);
        trips.cancel(driverId, withdrawn);

        assertThat(intent(declined)).containsEntry("status", "VOID").containsEntry("amount_due", null);
        assertThat(intent(cancelled)).containsEntry("status", "VOID");
        assertThat(ledger(declined)).isEmpty();
        assertThat(payments.owes(passengerId)).isFalse();
        assertThat(payments.forPassenger(passengerId)).allSatisfy(payment -> {
            assertThat(payment.payAfterRide()).isTrue();
            assertThat(payment.status()).isEqualTo("VOID");
        });
    }

    @Test
    void aNoShowOwesEverything() {
        long rideId = ride(Duration.ofMinutes(30), true, true);
        Booking booking = later(passengerId, rideId);
        trips.start(driverId, rideId);

        trips.noShow(driverId, booking.id());

        assertThat(intent(booking)).containsEntry("status", "DUE").containsEntry("due_reason", "PASSENGER_NO_SHOW");
        assertThat(payments.due(booking.id())).hasValueSatisfying(due -> assertThat(due).isEqualByComparingTo("572.00"));
    }

    @Test
    void onlyThePassengerPaysOnlyWhatIsDueAndADeclineChangesNothing() {
        long rideId = ride(Duration.ofMinutes(30), true, true);
        Booking booking = later(passengerId, rideId);

        assertThatThrownBy(() -> bookings.payDue(passengerId, booking.id(), PaymentChoice.card()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("Nothing is due on this booking"));
        trips.start(driverId, rideId);
        trips.complete(driverId, rideId);
        assertThatThrownBy(() -> bookings.payDue(user(UserRole.PASSENGER), booking.id(), PaymentChoice.card()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> bookings.payDue(passengerId, booking.id(),
                new PaymentChoice(PaymentChoice.Method.CARD, "DECLINE")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));

        assertThat(intent(booking)).containsEntry("status", "DUE");
        assertThat(ledger(booking)).isEmpty();
    }

    @Test
    void aReceiptComesOnceItIsPaidAndShowsWhatWasPaid() {
        Booking booking = later(passengerId, ride(Duration.ofHours(30), true, true));
        bookings.cancel(passengerId, booking.id());

        assertThatThrownBy(() -> receipts.booking(passengerId, booking.id()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        bookings.payDue(passengerId, booking.id(), PaymentChoice.card());

        ReceiptService.BookingReceipt receipt = receipts.booking(passengerId, booking.id());
        assertThat(receipt.fare()).isEqualByComparingTo("260.00");
        assertThat(receipt.serviceFee()).isEqualByComparingTo("52.00");
        assertThat(receipt.total()).isEqualByComparingTo("312.00");
        assertThat(receipt.method()).isEqualTo("CARD");
        assertThat(receipt.paidAt()).isNotNull();
        assertThat(ledger(booking)).containsExactly("HOLD 312.00", "CAPTURE 312.00", "PAYOUT 260.00");
    }

    @Test
    void aFlexibleBookingPaidAfterTheRideIsRebookedOnlyWhereThatIsAccepted() {
        LocalDate day = LocalDate.now(ZONE).plusDays(23);
        long withdrawn = ride(driverId, day.atTime(7, 0).atZone(ZONE).toInstant(), true, true);
        // Closer, but paid when booking.
        ride(user(UserRole.DRIVER), day.atTime(7, 20).atZone(ZONE).toInstant(), true, false);
        long accepting = ride(user(UserRole.DRIVER), day.atTime(8, 10).atZone(ZONE).toInstant(), true, true);
        bookings.request(passengerId, withdrawn, 0, 1, 1, null, null, FlexTier.FLEXIBLE, null, null, null, true);

        trips.cancel(driverId, withdrawn);

        assertThat(bookings.forPassenger(passengerId)).filteredOn(b -> b.status() == BookingStatus.CONFIRMED)
                .singleElement().satisfies(b -> {
                    assertThat(b.rideId()).isEqualTo(accepting);
                    assertThat(b.payAfterRide()).isTrue();
                    assertThat(b.paymentStatus()).isEqualTo("DEFERRED");
                });
    }

    @Test
    void theHttpApiBooksWithoutAPaymentMethodAndLetsEachSidePay() throws Exception {
        long rideId = ride(Duration.ofMinutes(30), true, true);
        String passenger = "Bearer " + tokens.issue(passengerId, UserRole.PASSENGER);
        String driver = "Bearer " + tokens.issue(driverId, UserRole.DRIVER);

        String created = mvc.perform(post("/api/v1/bookings").header("Authorization", passenger)
                        .contentType("application/json").content(json.writeValueAsString(Map.of(
                                "rideId", rideId, "fromSeq", 0, "toSeq", 1, "seats", 1, "payAfterRide", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payAfterRide").value(true))
                .andExpect(jsonPath("$.paymentStatus").value("DEFERRED"))
                .andReturn().getResponse().getContentAsString();
        long bookingId = json.readTree(created).get("id").asLong();
        trips.start(driverId, rideId);
        trips.complete(driverId, rideId);

        mvc.perform(post("/api/v1/bookings/" + bookingId + "/pay").header("Authorization", passenger)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/cash-received").header("Authorization", passenger))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/cash-received").header("Authorization", driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("SETTLED"))
                .andExpect(jsonPath("$.amountDue").value(572.0));
    }

    /** What a pay-after-ride booking owes after its passenger cancels this long before boarding. */
    private String cancelled(Duration untilDeparture, boolean instantBooking) {
        long passenger = user(UserRole.PASSENGER);
        Booking booking = later(passenger, ride(untilDeparture, instantBooking, true));
        bookings.cancel(passenger, booking.id());
        Map<String, Object> intent = intent(booking);
        return intent.get("amount_due") == null ? (String) intent.get("status")
                : intent.get("status") + " " + intent.get("amount_due") + " " + intent.get("due_reason");
    }

    private Booking later(long passenger, long rideId) {
        return bookings.request(passenger, rideId, 0, 1, 1, null, null, FlexTier.EXACT, null, null, null, true);
    }

    private List<String> ledger(Booking booking) {
        return jdbc.queryForList("""
                SELECT l.kind || ' ' || l.amount FROM ledger_entries l
                JOIN payment_intents p ON p.id = l.intent_id
                WHERE p.booking_id = ? ORDER BY l.id""", String.class, booking.id());
    }

    private Map<String, Object> intent(Booking booking) {
        return jdbc.queryForMap("""
                SELECT status, provider, method, amount_due, due_reason
                FROM payment_intents WHERE booking_id = ?""", booking.id());
    }

    private int seatsAvailable(long rideId) {
        return jdbc.queryForObject(
                "SELECT seats_available FROM ride_segments WHERE ride_id = ?", Integer.class, rideId);
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test " + role, role).id();
    }

    private long ride(Duration untilDeparture, boolean instantBooking, boolean payAfterRide) {
        return ride(driverId, Instant.now().plus(untilDeparture), instantBooking, payAfterRide);
    }

    private long ride(long driver, Instant departure, boolean instantBooking, boolean payAfterRide) {
        long vehicleId = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        return rides.publish(driver, new PublishRide(vehicleId, departure, 3, instantBooking, STOPS,
                List.of(new BigDecimal("520.00")), null, false, null, 0, false, 0, payAfterRide)).id();
    }
}
