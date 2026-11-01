package com.dypcet.vayora.payment;

import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.payment.PaymentService.Payment;
import com.dypcet.vayora.payment.PaymentService.Settlement;
import com.dypcet.vayora.refund.RefundProperties;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A person's own money history, and what a driver holds from rides paid in cash. */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    /** @param atLeastHoursBefore cancelling this long before boarding, or earlier, earns the tier */
    public record RefundTier(long atLeastHoursBefore, BigDecimal fareShare) {
    }

    /**
     * What a passenger is charged and what cancelling returns, for the UI to state.
     *
     * @param provider          who handles payments: {@code MOCK} moves no money,
     *                          {@code RAZORPAY_TEST} is Razorpay's test mode, which moves none either
     * @param checkout          the passenger pays in Razorpay's checkout before booking or buying
     * @param flexibleTiers     whether a booking may be flexible: not while only the passenger can pay
     */
    public record Terms(String provider, BigDecimal serviceFeeRate, List<RefundTier> refundTiers,
            boolean checkout, boolean flexibleTiers) {
    }

    /** @param held the platform's fees inside what a driver took in cash, which they hold for it */
    public record CashFees(BigDecimal held) {
    }

    private final PaymentService payments;
    private final Terms terms;

    public PaymentController(PaymentService payments, PaymentProvider provider, ServiceFee serviceFee,
            RefundProperties refunds) {
        this.payments = payments;
        this.terms = new Terms(provider.name(), serviceFee.rate(), refunds.tiers().stream()
                .map(tier -> new RefundTier(tier.atLeastBefore().toHours(), tier.fareShare()))
                .toList(), provider.needsThePassengerToPay(), !provider.needsThePassengerToPay());
    }

    @GetMapping("/payment-terms")
    public Terms terms() {
        return terms;
    }

    @GetMapping("/payments")
    @PreAuthorize("hasRole('PASSENGER')")
    public List<Payment> payments(@AuthenticationPrincipal Jwt jwt) {
        return payments.forPassenger(CurrentUser.id(jwt));
    }

    @GetMapping("/settlements")
    @PreAuthorize("hasRole('DRIVER')")
    public List<Settlement> settlements(@AuthenticationPrincipal Jwt jwt) {
        return payments.forDriver(CurrentUser.id(jwt));
    }

    @GetMapping("/settlements/cash-fees")
    @PreAuthorize("hasRole('DRIVER')")
    public CashFees cashFees(@AuthenticationPrincipal Jwt jwt) {
        return new CashFees(payments.cashFeesHeld(CurrentUser.id(jwt)));
    }
}
