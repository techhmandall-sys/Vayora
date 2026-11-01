package com.dypcet.vayora.pass;

import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.pass.PassProperties.Kind;
import com.dypcet.vayora.pass.PassProperties.Offer;
import com.dypcet.vayora.pass.PassService.Pass;
import com.dypcet.vayora.payment.PaymentChoice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/passes")
public class PassController {

    public record BuyRequest(@NotNull Kind kind,
            // How to pay; may be left out when a checkout has paid, as Razorpay says how.
            PaymentChoice.Method paymentMethod,
            // Mock provider only: "DECLINE" makes the payment fail.
            @Size(max = 20) String mockOutcome,
            // What Razorpay's checkout handed back once the passenger paid (feature 46).
            @Valid PaymentChoice.Checkout razorpay) {
    }

    private final PassService passes;

    public PassController(PassService passes) {
        this.passes = passes;
    }

    /** What is on sale. The same for everyone. */
    @GetMapping("/offers")
    public List<Offer> offers() {
        return passes.offers();
    }

    @GetMapping
    @PreAuthorize("hasRole('PASSENGER')")
    public List<Pass> mine(@AuthenticationPrincipal Jwt jwt) {
        return passes.of(CurrentUser.id(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PASSENGER')")
    public Pass buy(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BuyRequest request) {
        return passes.buy(CurrentUser.id(jwt), request.kind(),
                PaymentChoice.of(request.paymentMethod(), request.mockOutcome(), request.razorpay()));
    }
}
