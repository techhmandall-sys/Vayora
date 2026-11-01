package com.dypcet.vayora.payment;

import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.pass.PassService;
import com.dypcet.vayora.pass.PassProperties.Kind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opens a Razorpay order for what the passenger is about to book or buy (feature 46). The
 * amount is the server's, worked out as the booking or pass will work it out, never the
 * browser's; the booking or pass then checks it was paid in full.
 */
@RestController
@RequestMapping("/api/v1")
public class CheckoutController {

    public record BookingCheckout(@NotNull Long rideId, @Min(0) int fromSeq, @Min(1) int toSeq,
            @Min(1) @Max(8) int seats) {
    }

    public record PassCheckout(@NotNull Kind kind) {
    }

    /**
     * What the browser's checkout needs.
     *
     * @param amount in paise, as Razorpay counts
     * @param keyId  the key's public half; never the secret
     */
    public record Order(String orderId, String keyId, long amount, String currency, boolean testMode) {
    }

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final RazorpayClient razorpay;
    private final PaymentService payments;
    private final PassService passes;
    private final Clock clock;

    public CheckoutController(RazorpayClient razorpay, PaymentService payments, PassService passes, Clock clock) {
        this.razorpay = razorpay;
        this.payments = payments;
        this.passes = passes;
        this.clock = clock;
    }

    @PostMapping("/bookings/checkout")
    @PreAuthorize("hasRole('PASSENGER')")
    public Order booking(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookingCheckout request) {
        long passengerId = CurrentUser.id(jwt);
        return order(payments.amountDue(passengerId, request.rideId(), request.fromSeq(), request.toSeq(),
                request.seats()), "booking", passengerId);
    }

    @PostMapping("/passes/checkout")
    @PreAuthorize("hasRole('PASSENGER')")
    public Order pass(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PassCheckout request) {
        long passengerId = CurrentUser.id(jwt);
        BigDecimal price = passes.offers().stream().filter(o -> o.kind() == request.kind()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "That pass is not on sale"))
                .price();
        return order(price, "pass", passengerId);
    }

    private Order order(BigDecimal rupees, String what, long passengerId) {
        if (!razorpay.configured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payments are simulated here: there is no checkout");
        }
        if (rupees.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is nothing to pay");
        }
        long paise = RazorpayPaymentProvider.paise(rupees);
        try {
            // A receipt is at most 40 characters: what, who and when.
            String orderId = razorpay.createOrder(paise,
                    what + "-" + passengerId + "-" + clock.instant().getEpochSecond());
            return new Order(orderId, razorpay.keyId(), paise, "INR", razorpay.testMode());
        } catch (RestClientException e) {
            log.warn("Razorpay did not open an order: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The payment service did not answer. Nothing was charged; try again.");
        }
    }
}
