package com.dypcet.vayora.payment;

import java.math.BigDecimal;
import java.util.Optional;

/** The outside party that actually moves money: the mock, or Razorpay when its keys are set. */
public interface PaymentProvider {

    /**
     * A payment the provider holds.
     *
     * @param reference the provider's own reference for it
     * @param method    how it was really paid, as the provider says
     */
    record Hold(String reference, PaymentChoice.Method method) {
    }

    /** Stored with every intent and settlement, so a record always says who handled it. */
    String name();

    /** Reserves the amount. Returns the hold, or empty if the provider declined. */
    Optional<Hold> hold(BigDecimal amount, PaymentChoice choice);

    void capture(String holdReference, BigDecimal amount);

    void release(String holdReference, BigDecimal amount);

    /** Pays a driver and returns the provider's reference for the payout. */
    String payout(long driverId, BigDecimal amount);

    /**
     * Whether a payment needs the passenger there to make it, in a checkout. If so, nothing
     * can be booked for them while they are away, such as a flexible rebooking.
     */
    default boolean needsThePassengerToPay() {
        return false;
    }
}
