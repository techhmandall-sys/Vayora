package com.dypcet.vayora.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dypcet.vayora.refund.RefundProperties.Tier;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundPolicyTest {

    private static final Tier FULL_AT_48H = new Tier(Duration.ofHours(48), new BigDecimal("1.0"));
    private static final Tier HALF_AT_24H = new Tier(Duration.ofHours(24), new BigDecimal("0.5"));

    private final RefundPolicy policy =
            new RefundPolicy(new RefundProperties(List.of(FULL_AT_48H, HALF_AT_24H)));
    private final BigDecimal fare = new BigDecimal("520.00");

    @Test
    void anEarlyCancellationRefundsTheWholeFare() {
        assertThat(policy.fareRefund(fare, Duration.ofDays(5))).isEqualByComparingTo("520.00");
    }

    @Test
    void theTierBoundaryItselfEarnsTheTier() {
        assertThat(policy.fareRefund(fare, Duration.ofHours(48))).isEqualByComparingTo("520.00");
        assertThat(policy.fareRefund(fare, Duration.ofHours(48).minusSeconds(1)))
                .isEqualByComparingTo("260.00");
        assertThat(policy.fareRefund(fare, Duration.ofHours(24))).isEqualByComparingTo("260.00");
    }

    @Test
    void aLateCancellationRefundsNothing() {
        assertThat(policy.fareRefund(fare, Duration.ofHours(24).minusSeconds(1)))
                .isEqualByComparingTo("0.00");
        assertThat(policy.fareRefund(fare, Duration.ZERO)).isEqualByComparingTo("0.00");
    }

    @Test
    void aRefundIsRoundedToTheNearestPaisa() {
        assertThat(policy.fareRefund(new BigDecimal("333.33"), Duration.ofHours(30)))
                .isEqualByComparingTo("166.67"); // 166.665 rounds half up
        assertThat(policy.fareRefund(new BigDecimal("333.33"), Duration.ofHours(30)).scale())
                .isEqualTo(2);
    }

    @Test
    void tiersThatGetMoreGenerousLaterOrAreOutOfOrderAreRefused() {
        Tier generousLate = new Tier(Duration.ofHours(24), new BigDecimal("1.0"));
        Tier stingyEarly = new Tier(Duration.ofHours(48), new BigDecimal("0.5"));

        assertThatThrownBy(() -> new RefundProperties(List.of(stingyEarly, generousLate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundProperties(List.of(HALF_AT_24H, FULL_AT_48H)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundProperties(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefundProperties(
                List.of(new Tier(Duration.ofHours(48), new BigDecimal("1.5")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
