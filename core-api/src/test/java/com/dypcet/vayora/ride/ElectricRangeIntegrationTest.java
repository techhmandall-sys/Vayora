package com.dypcet.vayora.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.vehicle.VehicleService;
import com.dypcet.vayora.vehicle.VehicleService.Details;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Feature 75: an electric car is not published on a stretch between stops longer than it
 * covers on a full charge, by the driver's own figures. The tests have no routing service,
 * so stretches are straight lines: Kolhapur to Satara about 111 km, Satara to Pune about
 * 95 km, Kolhapur to Pune about 206 km.
 */
class ElectricRangeIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    // A few metres from the town centres other tests use, so no route they cached is reused.
    private static final StopInput KOLHAPUR = new StopInput("Kolhapur", 16.7052, 74.2431, 0);
    private static final StopInput SATARA = new StopInput("Satara", 17.6807, 74.0181, 130);
    private static final StopInput PUNE = new StopInput("Pune", 18.5206, 73.8565, 270);
    // 22.5 kWh at 15 kWh per 100 km: 150 km.
    private static final Details SHORT_RANGE =
            new Details(null, null, true, new BigDecimal("22.5"), new BigDecimal("15.0"));

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private JdbcTemplate jdbc;

    private long driver;
    private Instant departure;

    @BeforeEach
    void setUp() {
        driver = identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test driver", UserRole.DRIVER).id();
        departure = LocalDate.now(ZONE).plusDays(2).atTime(7, 0).atZone(ZONE).toInstant();
    }

    @Test
    void aStretchLongerThanTheRangeIsRefusedAndNothingIsPublished() {
        long ev = vehicles.create(driver, "Tata", "Tiago EV", 2024, "Blue", 5, SHORT_RANGE).id();

        assertThatThrownBy(() -> publish(ev, List.of(KOLHAPUR, PUNE), null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("A stretch between two stops is longer than your vehicle"
                            + " covers on a full charge, by your own figures. Add a stop where you can charge.");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rides WHERE driver_id = ?", Integer.class, driver))
                .isZero();
    }

    @Test
    void theSameRoadWithAStopToChargeAtIsPublishedAndSoIsItsReturn() {
        long ev = vehicles.create(driver, "Tata", "Tiago EV", 2024, "Blue", 5, SHORT_RANGE).id();

        publish(ev, List.of(KOLHAPUR, SATARA, PUNE), departure.plusSeconds(12 * 3600));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rides WHERE driver_id = ?", Integer.class, driver))
                .isEqualTo(2);
    }

    @Test
    void aCarWithNoStatedRangeIsNeverRefused() {
        long petrol = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        long halfSaid = vehicles.create(driver, "MG", "ZS EV", null, null, 5,
                new Details(null, null, true, new BigDecimal("50.3"), null)).id();

        publish(petrol, List.of(KOLHAPUR, PUNE), null);
        publish(halfSaid, List.of(KOLHAPUR, PUNE), null);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rides WHERE driver_id = ?", Integer.class, driver))
                .isEqualTo(2);
    }

    private void publish(long vehicleId, List<StopInput> stops, Instant returnAt) {
        List<BigDecimal> prices = stops.subList(1, stops.size()).stream().map(s -> new BigDecimal("250.00")).toList();
        rides.publish(driver, new PublishRide(vehicleId, departure, 3, true, stops, prices,
                null, false, returnAt, 0, false, 0));
    }
}
