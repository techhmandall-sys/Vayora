package com.dypcet.vayora.ride;

import static org.assertj.core.api.Assertions.assertThat;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.ride.MatchScore.Reason;
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

/** Feature 76 in search: "best match" puts the driver the passenger agrees with first. */
class MatchScoringIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Point KOLHAPUR = new Point(16.7050, 74.2433);
    private static final Point PUNE = new Point(18.5204, 73.8567);

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private JdbcTemplate jdbc;

    private LocalDate day;
    private long passenger;
    private long agreeingRide;
    private long differingRide;

    @BeforeEach
    void setUp() {
        // A day of its own, so other tests' rides cannot appear in these searches.
        day = LocalDate.now(ZONE).plusDays(3);
        passenger = user(UserRole.PASSENGER, false, "QUIET");
        // The differing driver leaves first, so time alone would put them first.
        differingRide = ride(user(UserRole.DRIVER, true, "TALKATIVE"), 8);
        agreeingRide = ride(user(UserRole.DRIVER, false, "QUIET"), 9);
    }

    @Test
    void bestMatchPutsTheDriverThePassengerAgreesWithFirstAndSaysWhy() {
        List<Candidate> best = ours(rides.search(passenger, KOLHAPUR, PUNE, day, 1, Sort.BEST_MATCH));

        assertThat(best).extracting(Candidate::rideId).containsExactly(agreeingRide, differingRide);
        assertThat(best.get(0).matchScore()).isGreaterThan(best.get(1).matchScore());
        assertThat(best.get(0).matchReasons()).contains(Reason.SAME_SMOKING, Reason.SAME_CONVERSATION);
        assertThat(best.get(1).matchReasons()).contains(Reason.DIFFERENT_SMOKING, Reason.DIFFERENT_CONVERSATION);
    }

    @Test
    void everyOtherOrderIsUntouchedButStillCarriesTheScore() {
        List<Candidate> byTime = ours(rides.search(passenger, KOLHAPUR, PUNE, day, 1, Sort.TIME));

        assertThat(byTime).extracting(Candidate::rideId).containsExactly(differingRide, agreeingRide);
        assertThat(byTime).allSatisfy(c -> assertThat(c.matchScore()).isBetween(0, 100));
    }

    @Test
    void aPassengerWhoHasSaidNothingIsMatchedOnDistancePriceAndRecordAlone() {
        long silent = user(UserRole.PASSENGER, null, null);

        List<Candidate> best = ours(rides.search(silent, KOLHAPUR, PUNE, day, 1, Sort.BEST_MATCH));

        assertThat(best).extracting(Candidate::matchScore).containsOnly(best.get(0).matchScore());
        assertThat(best).allSatisfy(c -> assertThat(c.matchReasons())
                .doesNotContain(Reason.SAME_SMOKING, Reason.DIFFERENT_SMOKING, Reason.SAME_CONVERSATION,
                        Reason.DIFFERENT_CONVERSATION));
    }

    private List<Candidate> ours(List<Candidate> found) {
        return found.stream().filter(c -> c.rideId() == agreeingRide || c.rideId() == differingRide).toList();
    }

    private long ride(long driver, int hour) {
        long vehicleId = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        return rides.publish(driver, new PublishRide(vehicleId, day.atTime(hour, 0).atZone(ZONE).toInstant(), 3,
                true, List.of(new StopInput("Kolhapur", KOLHAPUR.lat(), KOLHAPUR.lon(), 0),
                        new StopInput("Pune", PUNE.lat(), PUNE.lon(), 270)),
                List.of(new BigDecimal("400.00")))).id();
    }

    /** A member who said whether smoking is fine with them, and how much they like to talk. */
    private long user(UserRole role, Boolean smokingFine, String chattiness) {
        long id = identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test " + role, role).id();
        jdbc.update("""
                INSERT INTO profiles (user_id, smoking, chattiness) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET smoking = EXCLUDED.smoking, chattiness = EXCLUDED.chattiness""",
                id, smokingFine, chattiness);
        return id;
    }
}
