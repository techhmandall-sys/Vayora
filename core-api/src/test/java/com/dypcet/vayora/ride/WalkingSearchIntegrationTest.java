package com.dypcet.vayora.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.ride.RideService.Candidate;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.Sort;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.routing.RouteService.Point;
import com.dypcet.vayora.routing.WalkingRoutes;
import com.dypcet.vayora.vehicle.VehicleService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Feature 91 in search: walking times on footpaths when the walking service answers. */
class WalkingSearchIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Point KOLHAPUR = new Point(16.7050, 74.2433);
    private static final Point PUNE = new Point(18.5204, 73.8567);
    // About a kilometre from the Kolhapur stop.
    private static final Point NEAR_KOLHAPUR = new Point(16.7140, 74.2433);

    @MockitoBean
    private WalkingRoutes walking;

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    private LocalDate day;
    private long rideId;

    @BeforeEach
    void setUp() {
        // A day of its own, so other tests' rides cannot appear in these searches.
        day = LocalDate.now(ZONE).plusDays(9);
        long driver = identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test DRIVER", UserRole.DRIVER).id();
        long vehicleId = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        rideId = rides.publish(driver, new PublishRide(vehicleId, day.atTime(9, 0).atZone(ZONE).toInstant(), 3, true,
                List.of(new StopInput("Kolhapur", KOLHAPUR.lat(), KOLHAPUR.lon(), 0),
                        new StopInput("Pune", PUNE.lat(), PUNE.lon(), 270)),
                List.of(new BigDecimal("400.00")))).id();
    }

    @Test
    void footpathTimesReplaceTheStraightLineEstimateAndSaySo() {
        when(walking.seconds(any(), any())).thenReturn(OptionalDouble.of(1130.0));

        Candidate found = search();

        assertThat(found.walkOnFootpaths()).isTrue();
        assertThat(found.pickupWalkMinutes()).isEqualTo(19); // 1130 s, rounded up
        assertThat(found.dropoffWalkMinutes()).isEqualTo(19);
    }

    @Test
    void withoutAFootpathAnswerTheStraightLineEstimateStaysAndIsSaidToBeOne() {
        when(walking.seconds(any(), any())).thenReturn(OptionalDouble.empty());

        Candidate found = search();

        assertThat(found.walkOnFootpaths()).isFalse();
        // About 1 km in a straight line at 4.5 km/h.
        assertThat(found.pickupWalkMinutes()).isBetween(13, 14);
    }

    private Candidate search() {
        long passenger = identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test PASSENGER", UserRole.PASSENGER).id();
        return rides.search(passenger, NEAR_KOLHAPUR, PUNE, day, 1, Sort.TIME).stream()
                .filter(c -> c.rideId() == rideId).findFirst().orElseThrow();
    }
}
