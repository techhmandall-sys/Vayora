package com.dypcet.vayora.eventlog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

/** The writer must never throw into its caller, whatever goes wrong. */
class EventLogServiceTest {

    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private EventLogService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = new EventLogService(jdbc, transactionManager, new ObjectMapper());
    }

    @Test
    void doesNotThrowWhenTheDatabaseIsUnreachable() {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new CannotCreateTransactionException("database is down"));

        assertThatCode(() -> service.write(EventType.BOOKING_ACCEPTED, Map.of("booking_id", 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotThrowWhenThePayloadCannotBeSerialized() {
        Object unserializable = new Object() {
            @SuppressWarnings("unused")
            public String getBroken() {
                throw new IllegalStateException("getter blew up");
            }
        };

        assertThatCode(() -> service.write(EventType.BOOKING_ACCEPTED, unserializable))
                .doesNotThrowAnyException();
        verifyNoInteractions(jdbc, transactionManager);
    }

    @Test
    void doesNotThrowWhenThePayloadRefersToItself() {
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);

        assertThatCode(() -> service.write(EventType.BOOKING_ACCEPTED, cyclic))
                .doesNotThrowAnyException();
        verifyNoInteractions(jdbc, transactionManager);
    }

    @Test
    void doesNotThrowWhenTheEventTypeIsNull() {
        assertThatCode(() -> service.write(null, Map.of())).doesNotThrowAnyException();
    }
}
