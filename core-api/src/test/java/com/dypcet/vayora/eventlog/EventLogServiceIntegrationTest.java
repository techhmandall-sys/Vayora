package com.dypcet.vayora.eventlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Writes through the real service into a real PostGIS, migrated by Flyway. */
@SpringBootTest
@Testcontainers
class EventLogServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant SIM_NOW = Instant.parse("2026-01-05T09:00:00Z");

    record Cancelled(long bookingId, Instant departureTime) {
    }

    record PassengerSearch(long passengerId, String origin) implements HasActor {
        @Override
        public String actorType() {
            return "passenger";
        }

        @Override
        public Long actorId() {
            return passengerId;
        }
    }

    @Autowired
    private EventLogService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void emptyTheLog() {
        jdbc.update("TRUNCATE events");
    }

    @AfterEach
    void clearRunContext() {
        RunContextHolder.clear();
    }

    @Test
    void productionWriteHasNoRunContextAndUsesWallClock() {
        Instant before = Instant.now();

        service.write(EventType.SEARCH_ISSUED, Map.of("origin", "Kolhapur"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM events");
        assertThat(row.get("run_id")).isNull();
        assertThat(row.get("variant")).isNull();
        assertThat(row.get("seed")).isNull();
        assertThat(row.get("actor_type")).isNull();
        assertThat(row.get("actor_id")).isNull();
        assertThat(row.get("event_type")).isEqualTo("search_issued");
        assertThat(jdbc.queryForObject("SELECT payload->>'origin' FROM events", String.class))
                .isEqualTo("Kolhapur");
        Instant simTime = jdbc.queryForObject("SELECT sim_time FROM events", OffsetDateTime.class)
                .toInstant();
        assertThat(simTime).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    void simulationWriteCarriesRunContextAndSimulatedClock() {
        RunContextHolder.set(new RunContext(RUN_ID, "B3", 17, SIM_NOW));

        service.write(EventType.TRIP_COMPLETED, null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT run_id, variant, seed, event_type, payload::text AS payload FROM events");
        assertThat(row.get("run_id")).isEqualTo(RUN_ID);
        assertThat(row.get("variant")).isEqualTo("B3");
        assertThat(row.get("seed")).isEqualTo(17);
        assertThat(row.get("event_type")).isEqualTo("trip_completed");
        assertThat(row.get("payload")).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT sim_time FROM events", OffsetDateTime.class)
                .toInstant()).isEqualTo(SIM_NOW);
    }

    @Test
    void actorColumnsComeFromAPayloadThatHasAnActor() {
        service.write(EventType.SEARCH_ISSUED, new PassengerSearch(1001L, "Kolhapur"));

        Map<String, Object> row = jdbc.queryForMap("SELECT actor_type, actor_id FROM events");
        assertThat(row.get("actor_type")).isEqualTo("passenger");
        assertThat(row.get("actor_id")).isEqualTo(1001L);
    }

    @Test
    void payloadKeysAreSnakeCaseAndTimestampsAreIso() {
        Instant departure = Instant.parse("2026-01-06T09:00:00Z");

        service.write(EventType.BOOKING_CANCELLED, new Cancelled(42L, departure));

        assertThat(jdbc.queryForObject("SELECT payload->>'booking_id' FROM events", String.class))
                .isEqualTo("42");
        assertThat(jdbc.queryForObject(
                "SELECT CAST(payload->>'departure_time' AS timestamptz) FROM events",
                OffsetDateTime.class).toInstant()).isEqualTo(departure);
    }

    @Test
    void javaWrittenCancellationsAreReadCorrectlyByTheMetricQuery() throws IOException {
        // One cancellation 2 hours before departure (late), one 30 hours before (not late).
        RunContextHolder.set(new RunContext(RUN_ID, "B0", 1, SIM_NOW));
        service.write(EventType.BOOKING_CANCELLED,
                new Cancelled(1L, SIM_NOW.plus(Duration.ofHours(2))));
        service.write(EventType.BOOKING_CANCELLED,
                new Cancelled(2L, SIM_NOW.plus(Duration.ofHours(30))));

        Map<String, Object> row = new NamedParameterJdbcTemplate(jdbc).queryForMap(
                metricQuery("late_cancellation_share"),
                Map.of("run_id", RUN_ID, "variant", "B0"));

        assertThat(row.get("cancellations")).isEqualTo(2L);
        assertThat(row.get("late")).isEqualTo(1L);
        assertThat(row.get("missing_departure_time")).isEqualTo(0L);
        assertThat((BigDecimal) row.get("late_share")).isEqualByComparingTo("0.5");
    }

    @Test
    void failedEventInsertDoesNotPoisonTheCallersTransaction() {
        TransactionTemplate booking = new TransactionTemplate(transactionManager);

        // PostgreSQL rejects a NUL character inside jsonb, so the middle insert fails
        // at the database, inside the caller's transaction.
        assertThatCode(() -> booking.executeWithoutResult(status -> {
            service.write(EventType.BOOKING_REQUESTED, Map.of("step", "before"));
            service.write(EventType.BOOKING_REQUESTED, Map.of("step", "bad\u0000value"));
            service.write(EventType.BOOKING_ACCEPTED, Map.of("step", "after"));
        })).doesNotThrowAnyException();

        assertThat(jdbc.queryForList(
                "SELECT payload->>'step' FROM events ORDER BY id", String.class))
                .containsExactly("before", "after");
    }

    @Test
    void eventIsNotPersistedWhenTheCallersTransactionRollsBack() {
        TransactionTemplate booking = new TransactionTemplate(transactionManager);

        booking.executeWithoutResult(status -> {
            service.write(EventType.BOOKING_ACCEPTED, Map.of("booking_id", 7));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isZero();
    }

    /** Extracts one named query from the shared metrics.sql. */
    private static String metricQuery(String name) throws IOException {
        Path file = Path.of("..", "experiments", "analysis", "metrics.sql");
        String marker = "-- name: " + name;
        String sql = Files.readString(file);
        int start = sql.indexOf(marker);
        assertThat(start).as("query %s in %s", name, file).isNotNegative();
        int bodyStart = sql.indexOf('\n', start) + 1;
        int end = sql.indexOf("-- name:", bodyStart);
        String body = end < 0 ? sql.substring(bodyStart) : sql.substring(bodyStart, end);
        return body.strip().replaceAll(";$", "");
    }
}
