package com.dypcet.vayora.supply;

import static org.assertj.core.api.Assertions.assertThat;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.booking.TripService;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.notification.NotificationService;
import com.dypcet.vayora.notification.NotificationService.Type;
import com.dypcet.vayora.ride.RideService;
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
import org.springframework.jdbc.core.JdbcTemplate;

class SupplyGapIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    // Far from the corridor, so no other test's ride or search lands here.
    private static final Point FROM = new Point(21.1458, 79.0882);
    private static final Point TO = new Point(21.2514, 79.3260);

    @Autowired
    private SupplyGapService supply;

    @Autowired
    private RideService rides;

    @Autowired
    private TripService trips;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private IdentityService identity;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void searchesThatFoundNothingMakeAGapAndAnExperiencedDriverIsToldOnce() {
        String from = "Gapville " + UUID.randomUUID().toString().substring(0, 8);
        String to = "Gaptown " + UUID.randomUUID().toString().substring(0, 8);
        long experienced = user(UserRole.DRIVER);
        long newcomer = user(UserRole.DRIVER);
        long done = ride(experienced, from, to, Instant.now().plus(Duration.ofMinutes(30)));
        trips.start(experienced, done);
        trips.complete(experienced, done);
        LocalDate day = LocalDate.now(ZONE).plusDays(3);
        for (int i = 0; i < 3; i++) {
            assertThat(rides.search(user(UserRole.PASSENGER), FROM, TO, day, 1, Sort.TIME)).isEmpty();
        }
        // Two empty searches on another day are not enough to count.
        for (int i = 0; i < 2; i++) {
            rides.search(user(UserRole.PASSENGER), FROM, TO, day.plusDays(1), 1, Sort.TIME);
        }

        supply.detectAndNudge();

        assertThat(supply.upcoming()).filteredOn(g -> g.fromStop().equals(from)).singleElement().satisfies(gap -> {
            assertThat(gap.date()).isEqualTo(day);
            assertThat(gap.toStop()).isEqualTo(to);
            assertThat(gap.emptySearches()).isEqualTo(3);
            assertThat(gap.driversNudged()).isEqualTo(1);
        });
        assertThat(notifications.forUser(experienced)).filteredOn(n -> n.type() == Type.SUPPLY_NUDGE).singleElement()
                .satisfies(n -> assertThat(n.message()).isEqualTo("Passengers looked for a ride from " + from + " to " + to
                        + " on " + day + " and found none. If you are driving then, you could offer seats."));
        assertThat(notifications.forUser(newcomer)).noneMatch(n -> n.type() == Type.SUPPLY_NUDGE);

        // Run again: the gap is not logged twice and nobody is told twice.
        supply.detectAndNudge();
        assertThat(count("supply_gap_detected", "from_stop", from)).isEqualTo(1);
        assertThat(count("nudge_issued", "from_stop", from)).isEqualTo(1);

        // The driver offers the stretch that day: logged once, for the record.
        ride(experienced, from, to, day.atTime(8, 0).atZone(ZONE).toInstant());
        assertThat(count("nudge_converted", "from_stop", from)).isEqualTo(1);
    }

    private int count(String type, String key, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE run_id IS NULL AND event_type = ? AND payload->>? = ?",
                Integer.class, type, key, value);
    }

    private long ride(long driver, String from, String to, Instant at) {
        long vehicleId = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        return rides.publish(driver, new PublishRide(vehicleId, at, 3, true,
                List.of(new StopInput(from, FROM.lat(), FROM.lon(), 0), new StopInput(to, TO.lat(), TO.lon(), 45)),
                List.of(new BigDecimal("150.00")))).id();
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery", "Test " + role, role).id();
    }
}
