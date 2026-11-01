package com.dypcet.vayora.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * How the passenger chose to pay.
 *
 * @param method      as the passenger chose it; with a checkout, the provider says which was
 *                    really used, and that is what is recorded
 * @param mockOutcome only meaningful to the mock provider: {@code "DECLINE"} makes the hold
 *                    fail, so the failure path can be exercised. Null otherwise.
 * @param checkout    what Razorpay's checkout handed back once the passenger paid; null
 *                    with the mock provider, which needs no checkout
 */
public record PaymentChoice(Method method, String mockOutcome, Checkout checkout) {

    public enum Method {
        CARD,
        UPI,
        WALLET
    }

    /**
     * Razorpay's answer to a payment made in its checkout: the order paid, the payment, and
     * its signature, which only someone with the key secret could have made.
     */
    public record Checkout(@NotBlank @Size(max = 100) String orderId, @NotBlank @Size(max = 100) String paymentId,
            @NotBlank @Size(max = 200) String signature) {
    }

    public PaymentChoice(Method method, String mockOutcome) {
        this(method, mockOutcome, null);
    }

    /** A request's choice: a method, unless a checkout paid, when Razorpay says which it was. */
    public static PaymentChoice of(Method method, String mockOutcome, Checkout checkout) {
        if (method == null && checkout == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose how to pay");
        }
        return new PaymentChoice(method == null ? Method.CARD : method, mockOutcome, checkout);
    }

    public static PaymentChoice card() {
        return new PaymentChoice(Method.CARD, null);
    }
}
