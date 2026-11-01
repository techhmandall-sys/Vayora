package com.dypcet.vayora.payment;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A stand-in for a payment gateway. It moves no money and talks to nobody: it approves
 * every hold unless asked to decline, and hands back made-up references.
 */
@Component
public class MockPaymentProvider implements PaymentProvider {

    public static final String NAME = "MOCK";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Hold> hold(BigDecimal amount, PaymentChoice choice) {
        if ("DECLINE".equalsIgnoreCase(choice.mockOutcome())) {
            return Optional.empty();
        }
        return Optional.of(new Hold("mock_hold_" + UUID.randomUUID(), choice.method()));
    }

    @Override
    public void capture(String holdReference, BigDecimal amount) {
        // Nothing to do: there is no real hold.
    }

    @Override
    public void release(String holdReference, BigDecimal amount) {
        // Nothing to do: there is no real hold.
    }

    @Override
    public String payout(long driverId, BigDecimal amount) {
        return "mock_payout_" + UUID.randomUUID();
    }
}
