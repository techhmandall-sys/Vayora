package com.dypcet.vayora.stopover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.RoutePreview;
import com.dypcet.vayora.routing.RouteService.Point;
import com.dypcet.vayora.stopover.StopoverService.Along;
import com.dypcet.vayora.stopover.StopoverService.Kind;
import com.dypcet.vayora.stopover.StopoverService.NewStopover;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class StopoverIntegrationTest extends PostgresIntegrationTest {

    private static final Point KOLHAPUR = new Point(16.7050, 74.2433);
    private static final Point PUNE = new Point(18.5204, 73.8567);

    @Autowired
    private StopoverService stopovers;

    @Autowired
    private RideService rides;

    @Autowired
    private IdentityService identity;

    @Autowired
    private TokenService tokens;

    @Autowired
    private MockMvc mvc;

    private final List<Long> added = new ArrayList<>();

    @AfterEach
    void removeWhatWasAdded() {
        // Other tests preview routes on the same corridor; leave nothing behind for them.
        added.forEach(id -> stopovers.remove(admin(), id));
    }

    @Test
    void aRoutePreviewNamesTheStopoversNearItInOrderAndSuggestsABreakNearTheTwoHourMark() {
        long earlyRest = add(Kind.REST, "Early rest", onTheLine(0.05), null);
        long charger = add(Kind.CHARGER, "Charger", onTheLine(0.27), new BigDecimal("60"));
        long midRest = add(Kind.REST, "Halfway rest", onTheLine(0.5), null);
        add(Kind.REST, "Mumbai rest", new Point(19.0760, 72.8777), null);

        RoutePreview preview = rides.preview(List.of(KOLHAPUR, PUNE));

        assertThat(preview.alongRoute()).extracting(a -> a.stopover().id()).containsExactly(earlyRest, charger, midRest);
        Along halfway = preview.alongRoute().get(2);
        assertThat(halfway.kmAlong()).isCloseTo(preview.distanceKm() / 2, org.assertj.core.data.Offset.offset(5.0));
        assertThat(halfway.kmOffRoute()).isLessThan(0.5);
        assertThat(preview.alongRoute().get(1).stopover().chargerKw()).isEqualByComparingTo("60");
        // Without a road route the drive is guessed at more than two hours, so one break is
        // suggested: the rest stop nearest the two-hour point, not the one near the start.
        assertThat(preview.durationMinutes()).isGreaterThan(120);
        assertThat(preview.alongRoute()).filteredOn(Along::suggestedBreak).extracting(a -> a.stopover().id())
                .containsExactly(midRest);
    }

    @Test
    void aShortRouteGetsNoSuggestedBreak() {
        add(Kind.REST, "Near start", onTheLine(0.02), null);
        RoutePreview preview = rides.preview(List.of(KOLHAPUR, onTheLine(0.1)));
        assertThat(preview.durationMinutes()).isLessThanOrEqualTo(120);
        assertThat(preview.alongRoute()).hasSize(1).noneMatch(Along::suggestedBreak);
    }

    @Test
    void onlyAnAdministratorAddsThemAndOnlyAChargerHasAPowerRating() throws Exception {
        String body = "{\"kind\": \"REST\", \"name\": \"Dhaba\", \"lat\": 17.6, \"lon\": 74.05%s}";
        String passenger = "Bearer " + tokens.issue(identity.register(UUID.randomUUID() + "@example.test",
                "correct-horse-battery", "Asha", UserRole.PASSENGER).id(), UserRole.PASSENGER);
        String admin = "Bearer " + tokens.issue(admin(), UserRole.ADMIN);

        mvc.perform(post("/api/v1/admin/stopovers").header("Authorization", passenger)
                        .contentType("application/json").content(body.formatted("")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/stopovers").header("Authorization", admin)
                        .contentType("application/json").content(body.formatted(", \"chargerKw\": 50")))
                .andExpect(status().isBadRequest());
        assertThat(stopovers.all()).noneMatch(s -> s.name().equals("Dhaba"));
    }

    /** A point this fraction of the way along the straight line from Kolhapur to Pune. */
    private static Point onTheLine(double fraction) {
        return new Point(KOLHAPUR.lat() + fraction * (PUNE.lat() - KOLHAPUR.lat()),
                KOLHAPUR.lon() + fraction * (PUNE.lon() - KOLHAPUR.lon()));
    }

    private long add(Kind kind, String name, Point at, BigDecimal kw) {
        long id = stopovers.add(admin(), new NewStopover(kind, name, at.lat(), at.lon(), kw, null)).id();
        added.add(id);
        return id;
    }

    private long admin() {
        return identity.authenticate("admin@example.test", "admin-test-password").id();
    }
}
