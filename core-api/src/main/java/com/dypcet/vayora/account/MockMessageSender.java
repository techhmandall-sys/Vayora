package com.dypcet.vayora.account;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends nothing to anyone. Each message is written to the log and the most recent are
 * kept in memory, where an administrator can read them, so that a flow which needs a
 * code can be completed without a real email or SMS provider.
 *
 * <p>The messages are never written to the database: some carry one-time codes.
 */
@Component
public class MockMessageSender implements MessageSender {

    public static final String NAME = "MOCK";

    public record Sent(Channel channel, String to, String text, Instant at) {
    }

    private static final Logger log = LoggerFactory.getLogger(MockMessageSender.class);
    private static final int KEPT = 50;

    private final Deque<Sent> outbox = new ArrayDeque<>();
    private final Clock clock;

    public MockMessageSender(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name(Channel channel) {
        return NAME;
    }

    @Override
    public synchronized void send(Channel channel, String to, String text) {
        log.info("MOCK {} to {} (not sent to anyone): {}", channel, to, text);
        outbox.addFirst(new Sent(channel, to, text, clock.instant()));
        while (outbox.size() > KEPT) {
            outbox.removeLast();
        }
    }

    /** The most recent messages, newest first. */
    public synchronized List<Sent> outbox() {
        return List.copyOf(outbox);
    }
}
