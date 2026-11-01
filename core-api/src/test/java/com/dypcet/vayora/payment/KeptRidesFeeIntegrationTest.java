package com.dypcet.vayora.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.booking.BookingService;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.Candidate;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.Sort;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.routing.RouteService.Point;
import com.dypcet.vayora.vehicle.VehicleService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Feature 94: a lower service fee on rides by a driver who keeps their rides, from their
 * record alone. The shipped settings: at least 10 completed, none withdrawn in 90 days, 7%
 * against the standard 10%.
 */
class KeptRidesFeeIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Point KOLHAPUR = new Point(16.7050, 74.2433);
    private static final Point PUNE = new Point(18.5204, 73.8567);
    private static final List<StopInput> STOPS = List.of(
            new StopInput("Kolhapur", KOLHAPUR.lat(), KOLHAPUR.lon(), 0),
            new StopInput("Pune", PUNE.lat(), PUNE.lon(), 270));
    private static final BigDecimal FARE = new BigDecimal("400.00");

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private BookingService bookings;

    @Autowired
    private JdbcTemplate jdbc;

    private long driverId;
    private long vehicleId;
    // A day of its own, so other tests' rides cannot appear in these searches.
    private LocalDate day;

    @BeforeEach
    void setUp() {
        driverId = user(UserRole.DRIVER);
        vehicleId = vehicles.create(driverId, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        day = LocalDate.now(ZONE).plusDays(5);
    }

    @Test
    void aDriverWhoKeepsTheirRidesEarnsTheirPassengersTheLowerFeeAndThatIsWhatIsHeld() {
        pastRides(10, "COMPLETED", 30);
        long rideId = ride();
        long passenger = user(UserRole.PASSENGER);

        Candidate offered = offered(passenger, rideId);
        assertThat(offered.keptRides()).isTrue();
        assertThat(offered.serviceFee()).isEqualByComparingTo("28.00"); // 7% of 400

        long bookingId = bookings.request(passenger, rideId, 0, 1, 1, PaymentChoice.card(), null).id();
        assertThat(jdbc.queryForObject("SELECT service_fee FROM payment_intents WHERE booking_id = ?",
                BigDecimal.class, bookingId)).isEqualByComparingTo("28.00");
    }

    @Test
    void tooFewCompletedRidesEarnNothing() {
        pastRides(9, "COMPLETED", 30);
        long rideId = ride();

        Candidate offered = offered(user(UserRole.PASSENGER), rideId);
        assertThat(offered.keptRides()).isFalse();
        assertThat(offered.serviceFee()).isEqualByComparingTo("40.00"); // the standard 10%
    }

    @Test
    void aWithdrawalInTheWindowLosesItAndOneFromLongAgoDoesNot() {
        pastRides(10, "COMPLETED", 30);
        long withdrawn = pastRides(1, "CANCELLED", 20).get(0);
        long rideId = ride();
        long passenger = user(UserRole.PASSENGER);

        assertThat(offered(passenger, rideId).serviceFee()).isEqualByComparingTo("40.00");

        jdbc.update("UPDATE rides SET departure_time = now() - interval '100 days' WHERE id = ?", withdrawn);
        assertThat(offered(passenger, rideId).serviceFee()).isEqualByComparingTo("28.00");
    }

    @Test
    void aWithdrawnRideStillToComeCountsAgainstTheDriver() {
        pastRides(10, "COMPLETED", 30);
        long future = ride();
        jdbc.update("UPDATE rides SET status = 'CANCELLED' WHERE id = ?", future);

        assertThat(offered(user(UserRole.PASSENGER), ride()).keptRides()).isFalse();
    }

    @Test
    void theLowerRateCanNeverBeAboveTheStandard() {
        assertThatThrownBy(() -> new ServiceFee(new BigDecimal("0.10"), new BigDecimal("0.12")))
                .isInstanceOf(IllegalArgumentException.class);
        ServiceFee fee = new ServiceFee(new BigDecimal("0.10"), new BigDecimal("0.07"));
        assertThat(fee.on(FARE, true)).isEqualByComparingTo("28.00");
        assertThat(fee.on(FARE, false)).isEqualByComparingTo("40.00");
        assertThat(fee.on(FARE)).isEqualByComparingTo("40.00");
    }

    /** Rides by this driver that left {@code daysAgo} days ago, ended as {@code status}. */
    private List<Long> pastRides(int count, String status, int daysAgo) {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = ride();
            jdbc.update("""
                    UPDATE rides SET status = ?, departure_time = now() - make_interval(days => ?)
                    WHERE id = ?""", status, daysAgo, id);
            ids.add(id);
        }
        return ids;
    }

    private long ride() {
        return rides.publish(driverId, new PublishRide(vehicleId,
                day.atTime(9, 0).atZone(ZONE).toInstant(), 3, true, STOPS, List.of(FARE))).id();
    }

    private Candidate offered(long passenger, long rideId) {
        return rides.search(passenger, KOLHAPUR, PUNE, day, 1, Sort.TIME).stream()
                .filter(c -> c.rideId() == rideId).findFirst().orElseThrow();
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test " + role, role).id();
    }
}
