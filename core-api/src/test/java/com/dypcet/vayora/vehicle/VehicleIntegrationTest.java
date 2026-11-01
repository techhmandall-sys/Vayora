package com.dypcet.vayora.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.ride.RideService;
import com.dypcet.vayora.ride.RideService.Candidate;
import com.dypcet.vayora.ride.RideService.PublishRide;
import com.dypcet.vayora.ride.RideService.Sort;
import com.dypcet.vayora.ride.RideService.StopInput;
import com.dypcet.vayora.routing.RouteService.Point;
import com.dypcet.vayora.vehicle.VehicleService.Comfort;
import com.dypcet.vayora.vehicle.VehicleService.Details;
import com.dypcet.vayora.vehicle.VehicleService.Luggage;
import com.dypcet.vayora.vehicle.VehicleService.Vehicle;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@AutoConfigureMockMvc
class VehicleIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Point KOLHAPUR = new Point(16.7050, 74.2433);
    private static final Point PUNE = new Point(18.5204, 73.8567);
    private static final Details ELECTRIC = new Details(Comfort.PREMIUM, Luggage.MEDIUM, true,
            new BigDecimal("40.5"), new BigDecimal("15.0"));

    @Autowired
    private IdentityService identity;

    @Autowired
    private VehicleService vehicles;

    @Autowired
    private RideService rides;

    @Autowired
    private TokenService tokens;

    @Autowired
    private MockMvc mvc;

    private long driverId;

    @BeforeEach
    void setUp() {
        driverId = user(UserRole.DRIVER);
    }

    @Test
    void whatTheDriverSaysAboutTheCarIsKeptAndTheRangeFollowsFromTheirFigures() {
        Vehicle saved = vehicles.create(driverId, "Tata", "Nexon EV", 2024, "Teal", 5, ELECTRIC);

        assertThat(saved.comfort()).isEqualTo(Comfort.PREMIUM);
        assertThat(saved.luggage()).isEqualTo(Luggage.MEDIUM);
        assertThat(saved.electric()).isTrue();
        assertThat(saved.batteryKwh()).isEqualByComparingTo("40.5");
        assertThat(saved.consumptionKwhPer100Km()).isEqualByComparingTo("15.0");
        assertThat(saved.estimatedRangeKm()).isEqualTo(270); // 40.5 / 15.0 * 100
        assertThat(vehicles.ownedBy(driverId)).containsExactly(saved);
    }

    @Test
    void aVehicleWithNothingSaidAboutItSaysNothing() {
        Vehicle plain = vehicles.create(driverId, "Maruti", "Ertiga", 2022, "Silver", 7);

        assertThat(plain.comfort()).isNull();
        assertThat(plain.luggage()).isNull();
        assertThat(plain.electric()).isFalse();
        assertThat(plain.estimatedRangeKm()).isNull();

        // One figure without the other implies no range.
        Vehicle half = vehicles.create(driverId, "MG", "ZS EV", null, null, 5,
                new Details(null, null, true, new BigDecimal("50.3"), null));
        assertThat(half.estimatedRangeKm()).isNull();
    }

    @Test
    void theRangeIsRoundedDownNeverUp() {
        assertThat(VehicleService.rangeKm(new BigDecimal("30.2"), new BigDecimal("13.3"))).isEqualTo(227);
        assertThat(VehicleService.rangeKm(new BigDecimal("30.0"), null)).isNull();
        assertThat(VehicleService.rangeKm(null, new BigDecimal("13.3"))).isNull();
    }

    @Test
    void batteryFiguresAreRefusedOnAVehicleThatIsNotElectric() {
        assertThatThrownBy(() -> vehicles.create(driverId, "Maruti", "Ertiga", 2022, "Silver", 7,
                new Details(null, null, false, new BigDecimal("40.5"), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(vehicles.ownedBy(driverId)).isEmpty();
    }

    @Test
    void aSearchResultSaysWhatCarItIs() {
        // A day of its own, so other tests' rides cannot appear in this search.
        LocalDate day = LocalDate.now(ZONE).plusDays(5);
        long electric = vehicles.create(driverId, "Tata", "Nexon EV", 2024, "Teal", 5, ELECTRIC).id();
        long plainDriver = user(UserRole.DRIVER);
        long plain = vehicles.create(plainDriver, "Maruti", "Ertiga", 2022, "Silver", 7).id();
        long first = publish(driverId, electric, day, 9);
        long second = publish(plainDriver, plain, day, 10);

        List<Candidate> found = rides.search(user(UserRole.PASSENGER), KOLHAPUR, PUNE, day, 1, Sort.TIME)
                .stream().filter(c -> c.rideId() == first || c.rideId() == second).toList();

        assertThat(found).extracting(Candidate::vehicle).containsExactly("Tata Nexon EV", "Maruti Ertiga");
        assertThat(found).extracting(Candidate::comfort).containsExactly("PREMIUM", null);
        assertThat(found).extracting(Candidate::luggage).containsExactly("MEDIUM", null);
        assertThat(found).extracting(Candidate::electric).containsExactly(true, false);
    }

    @Test
    void theEndpointValidatesAndShowsADriverOnlyTheirOwn() throws Exception {
        String driver = "Bearer " + tokens.issue(driverId, UserRole.DRIVER);
        String other = "Bearer " + tokens.issue(user(UserRole.DRIVER), UserRole.DRIVER);
        String base = "{\"make\": \"Tata\", \"model\": \"Nexon EV\", \"seats\": 5%s}";

        mvc.perform(post("/api/v1/vehicles").header("Authorization", driver).contentType("application/json")
                        .content(String.format(base, ", \"comfort\": \"COMFORT\", \"luggage\": \"LARGE\","
                                + " \"electric\": true, \"batteryKwh\": 40.5, \"consumptionKwhPer100Km\": 15")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comfort").value("COMFORT"))
                .andExpect(jsonPath("$.luggage").value("LARGE"))
                .andExpect(jsonPath("$.electric").value(true))
                .andExpect(jsonPath("$.estimatedRangeKm").value(270));
        mvc.perform(post("/api/v1/vehicles").header("Authorization", driver).contentType("application/json")
                        .content(String.format(base, "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.electric").value(false))
                .andExpect(jsonPath("$.comfort").doesNotExist());
        for (String bad : List.of(", \"comfort\": \"LUXURY\"", ", \"luggage\": \"HUGE\"",
                ", \"electric\": true, \"batteryKwh\": 0", ", \"electric\": true, \"batteryKwh\": 40.55",
                ", \"electric\": true, \"consumptionKwhPer100Km\": 99", ", \"batteryKwh\": 40")) {
            mvc.perform(post("/api/v1/vehicles").header("Authorization", driver)
                            .contentType("application/json").content(String.format(base, bad)))
                    .andExpect(status().isBadRequest());
        }

        mvc.perform(get("/api/v1/vehicles").header("Authorization", driver))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/vehicles").header("Authorization", other))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private long publish(long driver, long vehicleId, LocalDate day, int hour) {
        return rides.publish(driver, new PublishRide(vehicleId,
                day.atTime(hour, 0).atZone(ZONE).toInstant(), 3, true,
                List.of(new StopInput("Kolhapur", KOLHAPUR.lat(), KOLHAPUR.lon(), 0),
                        new StopInput("Pune", PUNE.lat(), PUNE.lon(), 270)),
                List.of(new BigDecimal("450.00")))).id();
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test " + role, role).id();
    }
}
