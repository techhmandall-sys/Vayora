package com.dypcet.vayora.pass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.booking.BookingService;
import com.dypcet.vayora.booking.BookingService.Booking;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.pass.PassProperties.Kind;
import com.dypcet.vayora.pass.PassService.Pass;
import com.dypcet.vayora.payment.PaymentChoice;
import com.dypcet.vayora.ride.RideService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@AutoConfigureMockMvc
class PassIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Point KOLHAPUR = new Point(16.7050, 74.2433);
    private static final Point PUNE = new Point(18.5204, 73.8567);

    @Autowired
    private IdentityService identity;

    @Autowired
    private PassService passes;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private BookingService bookings;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    private long passenger;

    @BeforeEach
    void setUp() {
        passenger = user(UserRole.PASSENGER);
    }

    @Test
    void aPassRunsForItsDaysAndASecondOneStartsWhenTheFirstEnds() {
        Instant before = Instant.now();
        Pass weekly = passes.buy(passenger, Kind.WEEKLY, PaymentChoice.card());

        assertThat(weekly.price()).isEqualByComparingTo("99");
        assertThat(weekly.provider()).isEqualTo("MOCK");
        assertThat(weekly.startsAt()).isBetween(before, Instant.now());
        assertThat(Duration.between(weekly.startsAt(), weekly.endsAt())).isEqualTo(Duration.ofDays(7));
        assertThat(passes.covers(passenger, Instant.now())).isTrue();
        assertThat(passes.covers(passenger, weekly.endsAt())).isFalse();

        // Bought early, the next one wastes no days.
        Pass monthly = passes.buy(passenger, Kind.MONTHLY, PaymentChoice.card());
        assertThat(monthly.startsAt()).isEqualTo(weekly.endsAt());
        assertThat(monthly.endsAt()).isEqualTo(weekly.endsAt().plus(Duration.ofDays(30)));
        assertThat(passes.covers(passenger, weekly.endsAt())).isTrue();
        assertThat(passes.of(passenger)).extracting(Pass::kind).containsExactly(Kind.MONTHLY, Kind.WEEKLY);
    }

    @Test
    void aDeclinedPaymentBuysNothing() {
        assertThatThrownBy(() -> passes.buy(passenger, Kind.ANNUAL,
                new PaymentChoice(PaymentChoice.Method.UPI, "DECLINE")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));
        assertThat(passes.of(passenger)).isEmpty();
        assertThat(passes.covers(passenger, Instant.now())).isFalse();
    }

    @Test
    void whileAPassRunsTheServiceFeeIsWaivedAndTheFareIsUntouched() {
        LocalDate day = LocalDate.now(ZONE).plusDays(4);
        long driver = user(UserRole.DRIVER);
        long rideId = ride(driver, day);
        long other = user(UserRole.PASSENGER);
        passes.buy(passenger, Kind.WEEKLY, PaymentChoice.card());

        Candidate covered = candidate(passenger, day, rideId);
        Candidate charged = candidate(other, day, rideId);
        assertThat(covered.fare()).isEqualByComparingTo("500.00");
        assertThat(covered.serviceFee()).isEqualByComparingTo("0");
        assertThat(covered.total()).isEqualByComparingTo("500.00");
        assertThat(charged.serviceFee()).isEqualByComparingTo("50.00");

        Booking withPass = bookings.request(passenger, rideId, 0, 1, 1, PaymentChoice.card(), null);
        Booking without = bookings.request(other, rideId, 0, 1, 1, PaymentChoice.card(), null);
        assertThat(withPass.price()).isEqualByComparingTo(without.price()); // the driver's fare
        assertThat(withPass.serviceFee()).isEqualByComparingTo("0");
        assertThat(withPass.total()).isEqualByComparingTo("500.00");
        assertThat(without.total()).isEqualByComparingTo("550.00");
    }

    @Test
    void aPassThatHasRunOutWaivesNothing() {
        jdbc.update("""
                INSERT INTO passes (passenger_id, kind, price, starts_at, ends_at, method, provider, provider_ref)
                VALUES (?, 'WEEKLY', 99, now() - interval '8 days', now() - interval '1 day', 'UPI', 'MOCK', 'x')""",
                passenger);

        assertThat(passes.covers(passenger, Instant.now())).isFalse();
        // And a new one starts now, not when the old one ended.
        assertThat(passes.buy(passenger, Kind.WEEKLY, PaymentChoice.card()).startsAt())
                .isAfter(Instant.now().minusSeconds(60));
    }

    @Test
    void passengersBuyPassesAndAnyoneSignedInCanSeeTheOffers() throws Exception {
        String rider = "Bearer " + tokens.issue(passenger, UserRole.PASSENGER);
        String driver = "Bearer " + tokens.issue(user(UserRole.DRIVER), UserRole.DRIVER);

        mvc.perform(get("/api/v1/passes/offers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/passes/offers").header("Authorization", driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].kind").value("ANNUAL"))
                .andExpect(jsonPath("$[2].days").value(365));
        mvc.perform(post("/api/v1/passes").header("Authorization", driver).contentType("application/json")
                        .content("{\"kind\": \"WEEKLY\", \"paymentMethod\": \"UPI\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/passes").header("Authorization", rider).contentType("application/json")
                        .content("{\"kind\": \"DAILY\", \"paymentMethod\": \"UPI\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/passes").header("Authorization", rider).contentType("application/json")
                        .content("{\"kind\": \"WEEKLY\", \"paymentMethod\": \"UPI\", \"mockOutcome\": \"DECLINE\"}"))
                .andExpect(status().isPaymentRequired());
        mvc.perform(post("/api/v1/passes").header("Authorization", rider).contentType("application/json")
                        .content("{\"kind\": \"WEEKLY\", \"paymentMethod\": \"UPI\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("WEEKLY"));
        mvc.perform(get("/api/v1/passes").header("Authorization", rider))
                .andExpect(jsonPath("$.length()").value(1));
    }

    private Candidate candidate(long who, LocalDate day, long rideId) {
        return rides.search(who, KOLHAPUR, PUNE, day, 1, Sort.TIME).stream()
                .filter(c -> c.rideId() == rideId).findFirst().orElseThrow();
    }

    private long ride(long driver, LocalDate day) {
        long vehicleId = vehicles.create(driver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        return rides.publish(driver, new PublishRide(vehicleId, day.atTime(9, 0).atZone(ZONE).toInstant(), 3, true,
                List.of(new StopInput("Kolhapur", KOLHAPUR.lat(), KOLHAPUR.lon(), 0),
                        new StopInput("Pune", PUNE.lat(), PUNE.lon(), 270)),
                List.of(new BigDecimal("500.00")))).id();
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test " + role, role).id();
    }
}
