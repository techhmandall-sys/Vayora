package com.dypcet.vayora.eventlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes rows to the research event log.
 *
 * <p>The insert runs under a savepoint when the caller already has a transaction. That
 * gives two guarantees: a failed insert cannot poison the caller's transaction, and an
 * event is committed only if the business change it describes is committed too.
 */
@Service
public class EventLogService {

    private static final Logger log = LoggerFactory.getLogger(EventLogService.class);

    private static final String INSERT = """
            INSERT INTO events
                (run_id, variant, seed, sim_time, actor_type, actor_id, event_type, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))""";

    private static final int[] INSERT_TYPES = {
        Types.OTHER, Types.VARCHAR, Types.INTEGER, Types.TIMESTAMP_WITH_TIMEZONE,
        Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.VARCHAR,
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate savepoint;
    private final ObjectMapper payloadMapper;
    private final Clock clock;

    @Autowired
    public EventLogService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this(jdbc, transactionManager, objectMapper, Clock.systemUTC());
    }

    EventLogService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        // Payload keys are snake_case and timestamps ISO-8601, matching the Python
        // writer; metrics.sql reads both writers' rows with the same expressions.
        this.payloadMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.clock = clock;
    }

    /**
     * Records one event. Never throws: losing a research row must not fail a booking.
     */
    public void write(EventType type, Object payload) {
        try {
            String json = payload == null ? "{}" : payloadMapper.writeValueAsString(payload);
            RunContext run = RunContextHolder.get();
            Instant simTime = run != null && run.simTime() != null ? run.simTime() : clock.instant();
            HasActor actor = payload instanceof HasActor a ? a : null;

            Object[] args = {
                run == null ? null : run.runId(),
                run == null ? null : run.variant(),
                run == null ? null : run.seed(),
                OffsetDateTime.ofInstant(simTime, ZoneOffset.UTC),
                actor == null ? null : actor.actorType(),
                actor == null ? null : actor.actorId(),
                type.wireName(),
                json,
            };
            savepoint.executeWithoutResult(status -> jdbc.update(INSERT, args, INSERT_TYPES));
        } catch (Exception | StackOverflowError e) {
            log.error("Event log write failed; research row lost: type={}", type, e);
        }
    }
}
