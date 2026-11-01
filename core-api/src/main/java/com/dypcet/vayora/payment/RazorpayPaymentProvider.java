package com.dypcet.vayora.payment;

import com.dypcet.vayora.payment.PaymentChoice.Checkout;
import com.dypcet.vayora.payment.PaymentChoice.Method;
import com.dypcet.vayora.payment.RazorpayClient.Payment;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Payments through Razorpay (feature 46), in place of the mock when its keys are set.
 *
 * <p>The passenger pays in Razorpay's checkout before booking; the booking or pass then
 * carries the checkout's answer, and this checks it: the signature, the order, that this
 * payment has paid for nothing else, the amount and the way it was paid. The payment is
 * captured at once, so a "hold" here is money taken, and a release is a refund. If the
 * booking then fails for any reason, the payment is refunded when its transaction rolls
 * back. Drivers are not paid through Razorpay, which needs its separate payouts product:
 * a payout is recorded, never sent.
 */
public class RazorpayPaymentProvider implements PaymentProvider {

    public static final String NAME = "RAZORPAY";
    public static final String TEST_NAME = "RAZORPAY_TEST";

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentProvider.class);
    // The ways of paying the platform records; any other is refunded.
    private static final Map<String, Method> METHODS = Map.of("card", Method.CARD, "upi", Method.UPI,
            "wallet", Method.WALLET);

    private final RazorpayClient razorpay;
    private final JdbcTemplate jdbc;

    public RazorpayPaymentProvider(RazorpayClient razorpay, JdbcTemplate jdbc) {
        this.razorpay = razorpay;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return razorpay.testMode() ? TEST_NAME : NAME;
    }

    @Override
    public Optional<Hold> hold(BigDecimal amount, PaymentChoice choice) {
        Checkout checkout = choice.checkout();
        if (checkout == null
                || !razorpay.signatureValid(checkout.orderId(), checkout.paymentId(), checkout.signature())) {
            return Optional.empty();
        }
        String paymentId = checkout.paymentId();
        // One payment pays for one thing: whoever presents it first, under a lock on it.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", paymentId);
        Integer used = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM payment_intents WHERE provider_ref = ?)
                     + (SELECT COUNT(*) FROM passes WHERE provider_ref = ?)""",
                Integer.class, paymentId, paymentId);
        if (used != null && used > 0) {
            return Optional.empty();
        }
        try {
            Payment payment = razorpay.payment(paymentId);
            if (!checkout.orderId().equals(payment.orderId())
                    || !("authorized".equals(payment.status()) || "captured".equals(payment.status()))) {
                return Optional.empty();
            }
            // From here the money is the platform's to return: whatever undoes this booking
            // or pass, a rollback included, refunds it.
            refundIfRolledBack(paymentId, payment.amount());
            if ("authorized".equals(payment.status())) {
                razorpay.capture(paymentId, payment.amount());
            }
            Method method = METHODS.get(payment.method());
            if (payment.amount() != paise(amount) || !"INR".equals(payment.currency()) || method == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "What was paid is not what is due, or not a card, UPI or a wallet. It is being refunded in full, and nothing was bought.");
            }
            return Optional.of(new Hold(paymentId, method));
        } catch (RestClientException e) {
            log.warn("Razorpay did not answer for payment {}: {}", paymentId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The payment service did not answer. Nothing was bought; anything taken is refunded.");
        }
    }

    /** Taken in full when it was held: there is nothing left to capture. */
    @Override
    public void capture(String holdReference, BigDecimal amount) {
    }

    @Override
    public void release(String holdReference, BigDecimal amount) {
        try {
            razorpay.refund(holdReference, paise(amount));
        } catch (RestClientException e) {
            log.warn("Razorpay refused a refund of {} on payment {}: {}", amount, holdReference, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The refund could not be made just now. Nothing was changed; try again.");
        }
    }

    /** Recorded only: paying drivers needs Razorpay's payouts product, which is not set up. */
    @Override
    public String payout(long driverId, BigDecimal amount) {
        return "not-sent-" + UUID.randomUUID();
    }

    @Override
    public boolean needsThePassengerToPay() {
        return true;
    }

    private void refundIfRolledBack(String paymentId, long paise) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("A Razorpay payment is taken only inside a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        razorpay.refund(paymentId, paise);
                    } catch (RuntimeException e) {
                        log.error("Payment {} was taken for nothing and could not be refunded: {}",
                                paymentId, e.getMessage());
                    }
                }
            }
        });
    }

    static long paise(BigDecimal rupees) {
        return rupees.movePointRight(2).longValueExact();
    }
}
