package com.dypcet.vayora.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.Candidate;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.Sort;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.routing.RouteService.Point;
import com.dypcet.vayora.vehicle.VehicleService.Vehicle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Feature 58: insurance as the driver declares it, shown only while it covers the trip. */
class InsuranceIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Point KOLHAPUR = new Point(16.7050, 74.2433);
    private static final Point PUNE = new Point(18.5204, 73.8567);

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    private long driverId;
    private long vehicleId;
    // A day of its own, so other tests' rides cannot appear in these searches.
    private LocalDate day;

    @BeforeEach
    void setUp() {
        driverId = user(UserRole.DRIVER);
        vehicleId = vehicles.create(driverId, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        day = LocalDate.now(ZONE).plusDays(4);
    }

    @Test
    void theDriverDeclaresAndRemovesItAndNoOtherDriverCan() {
        Vehicle declared = vehicles.declareInsurance(vehicleId, driverId, "  New India Assurance ", day.plusMonths(6));

        assertThat(declared.insurer()).isEqualTo("New India Assurance");
        assertThat(declared.insuredUntil()).isEqualTo(day.plusMonths(6));
        long other = user(UserRole.DRIVER);
        assertNotFound(() -> vehicles.declareInsurance(vehicleId, other, "Someone Else", day));
        assertNotFound(() -> vehicles.clearInsurance(vehicleId, other));

        Vehicle cleared = vehicles.clearInsurance(vehicleId, driverId);
        assertThat(cleared.insurer()).isNull();
        assertThat(cleared.insuredUntil()).isNull();
    }

    @Test
    void anInsuranceThatHasEndedIsRefused() {
        assertThatThrownBy(() -> vehicles.declareInsurance(vehicleId, driverId, "New India Assurance",
                LocalDate.now(ZONE).minusDays(1)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("That insurance has already ended");
                });
    }

    @Test
    void passengersSeeItOnlyWhileItCoversTheDayOfTheTrip() {
        long rideId = ride();
        long passenger = user(UserRole.PASSENGER);

        assertThat(offered(passenger, rideId).insurer()).isNull();

        vehicles.declareInsurance(vehicleId, driverId, "New India Assurance", day);
        Candidate covered = offered(passenger, rideId);
        assertThat(covered.insurer()).isEqualTo("New India Assurance");
        assertThat(covered.insuredUntil()).isEqualTo(day);

        vehicles.declareInsurance(vehicleId, driverId, "New India Assurance", day.minusDays(1));
        Candidate lapsed = offered(passenger, rideId);
        assertThat(lapsed.insurer()).isNull();
        assertThat(lapsed.insuredUntil()).isNull();
    }

    private long ride() {
        return rides.publish(driverId, new PublishRide(vehicleId, day.atTime(9, 0).atZone(ZONE).toInstant(), 3,
                true, List.of(new StopInput("Kolhapur", KOLHAPUR.lat(), KOLHAPUR.lon(), 0),
                        new StopInput("Pune", PUNE.lat(), PUNE.lon(), 270)),
                List.of(new BigDecimal("400.00")))).id();
    }

    private Candidate offered(long passenger, long rideId) {
        return rides.search(passenger, KOLHAPUR, PUNE, day, 1, Sort.TIME).stream()
                .filter(c -> c.rideId() == rideId).findFirst().orElseThrow();
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test " + role, role).id();
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
