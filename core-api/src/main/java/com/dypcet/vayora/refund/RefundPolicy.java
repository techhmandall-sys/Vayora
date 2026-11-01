package com.dypcet.vayora.refund;

import com.dypcet.vayora.refund.RefundProperties.Tier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How much of the fare a passenger gets back for cancelling a booking a driver had
 * accepted. The earlier the cancellation, the more: an early release can be refilled.
 *
 * <p>This is a fixed policy that depends only on timing. It never looks at a predicted
 * risk, and the service fee is outside it.
 */
@Component
@EnableConfigurationProperties(RefundProperties.class)
public class RefundPolicy {

    private final RefundProperties properties;

    public RefundPolicy(RefundProperties properties) {
        this.properties = properties;
    }

    /** The part of {@code fare} returned when cancelling {@code beforeBoarding} ahead. */
    public BigDecimal fareRefund(BigDecimal fare, Duration beforeBoarding) {
        return fare.multiply(fareShare(beforeBoarding)).setScale(2, RoundingMode.HALF_UP);
    }

    BigDecimal fareShare(Duration beforeBoarding) {
        for (Tier tier : properties.tiers()) {
            if (beforeBoarding.compareTo(tier.atLeastBefore()) >= 0) {
                return tier.fareShare();
            }
        }
        return BigDecimal.ZERO;
    }
}
