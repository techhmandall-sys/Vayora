package com.dypcet.vayora.ride;

import static org.assertj.core.api.Assertions.assertThat;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.booking.TripService;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.ride.RideService.Candidate;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.Sort;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.routing.RouteService.Point;
import com.dypcet.vayora.vehicle.VehicleService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DriverRecordIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private TripService trips;

    @Test
    void aSearchResultShowsTheDriversCompletedAndWithdrawnRidesAndARoughWalk() {
        long driver = user(UserRole.DRIVER);
        long vehicleId = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        long done = publish(driver, vehicleId, Instant.now().plus(Duration.ofMinutes(30)));
        trips.start(driver, done);
        trips.complete(driver, done);
        trips.cancel(driver, publish(driver, vehicleId, Instant.now().plus(Duration.ofDays(1))));
        LocalDate day = LocalDate.now(ZONE).plusDays(10);
        long offered = publish(driver, vehicleId, day.atTime(9, 0).atZone(ZONE).toInstant());

        // About 1.1 km north of the Kolhapur stop.
        Candidate found = rides.search(user(UserRole.PASSENGER), new Point(16.7150, 74.2433),
                        new Point(18.5204, 73.8567), day, 1, Sort.TIME).stream()
                .filter(c -> c.rideId() == offered).findFirst().orElseThrow();

        assertThat(found.driverRidesCompleted()).isEqualTo(1);
        assertThat(found.driverRidesWithdrawn()).isEqualTo(1);
        assertThat(found.pickupDistanceM()).isBetween(1000L, 1200L);
        // At 4.5 km/h, rounded up.
        assertThat(found.pickupWalkMinutes()).isEqualTo((int) Math.ceil(found.pickupDistanceM() / 4500.0 * 60));
        assertThat(found.dropoffWalkMinutes()).isZero();
    }

    private long publish(long driver, long vehicleId, Instant at) {
        return rides.publish(driver, new PublishRide(vehicleId, at, 3, true,
                List.of(new StopInput("Kolhapur", 16.7050, 74.2433, 0), new StopInput("Pune", 18.5204, 73.8567, 270)),
                List.of(new BigDecimal("450.00")))).id();
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery", "Test " + role, role).id();
    }
}
