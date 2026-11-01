package com.dypcet.vayora.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.account.AccountService;
import com.dypcet.vayora.account.AccountService.IdDocument;
import com.dypcet.vayora.check.DocumentCheckService.Check;
import com.dypcet.vayora.check.DocumentCheckService.Kind;
import com.dypcet.vayora.check.DocumentCheckService.Outcome;
import com.dypcet.vayora.check.DocumentCheckService.Status;
import com.dypcet.vayora.check.DocumentCheckService.Waiting;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.notification.NotificationService;
import com.dypcet.vayora.rating.RatingService;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.Candidate;
import com.dypcet.vayora.ride.RideService.Filters;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Features 7, 8, 35 and 58: a person checks what a driver declared. The driver shows the
 * original to an administrator, who records the outcome; no number or copy is kept.
 */
@AutoConfigureMockMvc
class DocumentCheckIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Point KOLHAPUR = new Point(16.7050, 74.2433);
    private static final Point PUNE = new Point(18.5204, 73.8567);
    private static final List<StopInput> STOPS = List.of(
            new StopInput("Kolhapur", KOLHAPUR.lat(), KOLHAPUR.lon(), 0),
            new StopInput("Pune", PUNE.lat(), PUNE.lon(), 270));
    private static final Filters ONLY_ID_CHECKED =
            new Filters(null, null, null, false, false, false, false, false, false, null, false, false, null, null, true);

    @Autowired
    private DocumentCheckService checks;

    @Autowired
    private AccountService accounts;

    @Autowired
    private RatingService ratings;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private IdentityService identity;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    private long admin;
    private long driver;
    // A day of its own, so other tests' rides cannot appear in these searches.
    private LocalDate day;

    @BeforeEach
    void setUp() {
        admin = identity.authenticate("admin@example.test", "admin-test-password").id();
        driver = user(UserRole.DRIVER);
        day = LocalDate.now(ZONE).plusDays(5);
        jdbc.update("UPDATE rides SET status = 'CANCELLED' WHERE status = 'SCHEDULED'");
    }

    @Test
    void aDriverShowsTheDocumentAndTheProfileThenSaysAPersonSawIt() {
        accounts.declareId(driver, IdDocument.DRIVING_LICENCE);
        assertThat(ratings.profile(driver).idCheckedAt()).isNull();
        String level = ratings.profile(driver).level();

        Check asked = checks.request(driver, Kind.IDENTITY, null);

        assertThat(asked.status()).isEqualTo(Status.WAITING);
        Waiting queued = queued(asked.id());
        assertThat(queued.document()).isEqualTo("DRIVING_LICENCE");
        assertThat(queued.driverId()).isEqualTo(driver);
        // Asking proves nothing.
        assertThat(ratings.profile(driver).idCheckedAt()).isNull();

        Check decided = checks.decide(admin, asked.id(), Outcome.CHECKED, null);

        assertThat(decided.status()).isEqualTo(Status.CHECKED);
        assertThat(decided.decidedAt()).isNotNull();
        assertThat(checks.of(driver)).extracting(Check::status).containsExactly(Status.CHECKED);
        assertThat(checks.waiting()).noneMatch(waiting -> waiting.id() == asked.id());
        assertThat(ratings.profile(driver).idCheckedAt()).isNotNull();
        assertThat(ratings.profile(driver).level()).isEqualTo(level);
        assertThat(notifications.forUser(driver)).first().satisfies(
                notice -> assertThat(notice.message()).isEqualTo("VAYORA staff have checked your identity document."));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                WHERE actor_id = ? AND action = 'DOCUMENT_CHECKED' AND target_type = 'document_check' AND target_id = ?""",
                Integer.class, admin, asked.id())).isEqualTo(1);
    }

    @Test
    void aRefusalNeedsAReasonTellsTheDriverAndTheyMayAskAgain() {
        accounts.declareId(driver, IdDocument.PASSPORT);
        Check asked = checks.request(driver, Kind.IDENTITY, null);

        assertStatus(HttpStatus.BAD_REQUEST, () -> checks.decide(admin, asked.id(), Outcome.REFUSED, "   "));
        assertThat(queued(asked.id())).isNotNull();

        checks.decide(admin, asked.id(), Outcome.REFUSED, "  The photo was too dark to read.  ");

        assertThat(checks.of(driver)).singleElement().satisfies(check -> {
            assertThat(check.status()).isEqualTo(Status.REFUSED);
            assertThat(check.note()).isEqualTo("The photo was too dark to read.");
        });
        assertThat(notifications.forUser(driver)).first().satisfies(notice -> assertThat(notice.message())
                .isEqualTo("VAYORA staff could not confirm your identity document: The photo was too dark to read."));
        assertThat(ratings.profile(driver).idCheckedAt()).isNull();

        assertThat(checks.request(driver, Kind.IDENTITY, null).status()).isEqualTo(Status.WAITING);
        assertThat(checks.of(driver)).extracting(Check::status).containsExactly(Status.WAITING);
    }

    @Test
    void changingOrRemovingTheDocumentWithdrawsTheCheck() {
        accounts.declareId(driver, IdDocument.PASSPORT);
        checks.decide(admin, checks.request(driver, Kind.IDENTITY, null).id(), Outcome.CHECKED, null);
        assertThat(ratings.profile(driver).idCheckedAt()).isNotNull();

        accounts.declareId(driver, IdDocument.VOTER_ID);

        assertThat(ratings.profile(driver).idCheckedAt()).isNull();
        assertThat(checks.of(driver)).isEmpty();

        // A waiting check goes too, and one can be asked for again.
        Check again = checks.request(driver, Kind.IDENTITY, null);
        accounts.withdrawId(driver);
        assertThat(checks.waiting()).noneMatch(waiting -> waiting.id() == again.id());
        assertStatus(HttpStatus.CONFLICT, () -> checks.request(driver, Kind.IDENTITY, null));
    }

    @Test
    void thereMustBeSomethingDeclaredAndOnlyOneLiveCheckOfIt() {
        assertStatus(HttpStatus.CONFLICT, () -> checks.request(driver, Kind.IDENTITY, null));

        accounts.declareId(driver, IdDocument.PAN);
        Check asked = checks.request(driver, Kind.IDENTITY, null);
        assertStatus(HttpStatus.CONFLICT, () -> checks.request(driver, Kind.IDENTITY, null));

        checks.decide(admin, asked.id(), Outcome.CHECKED, null);
        assertStatus(HttpStatus.CONFLICT, () -> checks.request(driver, Kind.IDENTITY, null));
        // Decided once: not again, and not on an id that does not exist.
        assertStatus(HttpStatus.NOT_FOUND, () -> checks.decide(admin, asked.id(), Outcome.REFUSED, "No"));
        assertStatus(HttpStatus.NOT_FOUND, () -> checks.decide(admin, -1, Outcome.CHECKED, null));
    }

    @Test
    void insuranceIsCheckedForOneCarOfTheDriversOwnAndShowsOnResults() {
        long car = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        long passenger = user(UserRole.PASSENGER);
        long other = user(UserRole.DRIVER);
        long rideId = rides.publish(driver, ride(car, 9)).id();

        assertStatus(HttpStatus.BAD_REQUEST, () -> checks.request(driver, Kind.INSURANCE, null));
        assertStatus(HttpStatus.CONFLICT, () -> checks.request(driver, Kind.INSURANCE, car));
        vehicles.declareInsurance(car, driver, "New India Assurance", day.plusYears(1));
        assertStatus(HttpStatus.NOT_FOUND, () -> checks.request(other, Kind.INSURANCE, car));
        assertThat(result(passenger, rideId).insuranceChecked()).isFalse();

        Check asked = checks.request(driver, Kind.INSURANCE, car);
        Waiting queued = queued(asked.id());
        assertThat(queued.vehicle()).isEqualTo("Maruti Ertiga");
        assertThat(queued.insurer()).isEqualTo("New India Assurance");
        assertThat(queued.insuredUntil()).isEqualTo(day.plusYears(1));
        checks.decide(admin, asked.id(), Outcome.CHECKED, null);

        assertThat(result(passenger, rideId).insuranceChecked()).isTrue();
        // The driver's identity is a separate matter.
        assertThat(result(passenger, rideId).driverIdChecked()).isFalse();
        assertThat(notifications.forUser(driver)).first().satisfies(notice -> assertThat(notice.message())
                .isEqualTo("VAYORA staff have checked the insurance for Maruti Ertiga."));

        vehicles.clearInsurance(car, driver);
        assertThat(result(passenger, rideId).insuranceChecked()).isFalse();
        assertThat(result(passenger, rideId).insurer()).isNull();
    }

    @Test
    void aPassengerCanAskForDriversWhoseIdentityStaffHaveChecked() {
        long checked = user(UserRole.DRIVER);
        long passenger = user(UserRole.PASSENGER);
        long checkedRide = rides.publish(checked, ride(vehicles.create(checked, "Tata", "Nexon", 2023, "Blue", 5).id(), 9)).id();
        long otherRide = rides.publish(driver, ride(vehicles.create(driver, "Honda", "City", 2020, "Red", 5).id(), 10)).id();
        // Declared and asked for, but nobody has seen it yet.
        accounts.declareId(driver, IdDocument.PASSPORT);
        checks.request(driver, Kind.IDENTITY, null);
        accounts.declareId(checked, IdDocument.DRIVING_LICENCE);
        checks.decide(admin, checks.request(checked, Kind.IDENTITY, null).id(), Outcome.CHECKED, null);

        assertThat(rides.search(passenger, KOLHAPUR, PUNE, day, 1, Sort.TIME, ONLY_ID_CHECKED))
                .extracting(Candidate::rideId).containsExactly(checkedRide);
        assertThat(result(passenger, checkedRide).driverIdChecked()).isTrue();
        assertThat(result(passenger, otherRide).driverIdChecked()).isFalse();
    }

    @Test
    void overHttpOnlyDriversAskOnlyAdministratorsDecideAndNoNumberIsKept() throws Exception {
        accounts.declareId(driver, IdDocument.PASSPORT);
        String asDriver = bearer(driver, UserRole.DRIVER);
        String asAdmin = bearer(admin, UserRole.ADMIN);
        long passenger = user(UserRole.PASSENGER);

        mvc.perform(post("/api/v1/me/document-checks").header("Authorization", bearer(passenger, UserRole.PASSENGER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\": \"IDENTITY\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/document-checks").header("Authorization", asDriver)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/document-checks").header("Authorization", asDriver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"IDENTITY\", \"number\": \"Z1234567\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"));

        long id = checks.of(driver).get(0).id();
        assertThat(jdbc.queryForMap("SELECT * FROM document_checks WHERE id = ?", id).toString())
                .doesNotContain("Z1234567");

        mvc.perform(get("/api/v1/me/document-checks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/document-checks").header("Authorization", asDriver))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/document-checks/" + id + "/decide").header("Authorization", asDriver)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"CHECKED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/document-checks").header("Authorization", asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].document").value("PASSPORT"));
        mvc.perform(post("/api/v1/admin/document-checks/" + id + "/decide").header("Authorization", asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\": \"CHECKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED"));

        mvc.perform(get("/api/v1/me/document-checks").header("Authorization", asDriver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CHECKED"));
        mvc.perform(get("/api/v1/users/" + driver + "/profile").header("Authorization", asDriver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredId").value("PASSPORT"))
                .andExpect(jsonPath("$.idCheckedAt").isNotEmpty());
    }

    private Waiting queued(long checkId) {
        return checks.waiting().stream().filter(waiting -> waiting.id() == checkId).findFirst().orElseThrow();
    }

    private Candidate result(long passenger, long rideId) {
        return rides.search(passenger, KOLHAPUR, PUNE, day, 1, Sort.TIME).stream()
                .filter(candidate -> candidate.rideId() == rideId).findFirst().orElseThrow();
    }

    private PublishRide ride(long vehicleId, int hour) {
        return new PublishRide(vehicleId, day.atTime(hour, 0).atZone(ZONE).toInstant(), 3, true, STOPS,
                List.of(new BigDecimal("400.00")), null, false, null, 0, false);
    }

    private String bearer(long userId, UserRole role) {
        return "Bearer " + tokens.issue(userId, role);
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery", "Test " + role, role).id();
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }
}
