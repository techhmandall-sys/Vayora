package com.dypcet.vayora.aftertrip;

import com.dypcet.vayora.aftertrip.AfterTripService.Dispute;
import com.dypcet.vayora.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AfterTripController {

    public record ConfirmRequest(@NotNull Boolean travelled) {
    }

    private final AfterTripService afterTrip;

    public AfterTripController(AfterTripService afterTrip) {
        this.afterTrip = afterTrip;
    }

    /** The passenger's side: did this completed trip happen? */
    @PostMapping("/bookings/{id}/trip-confirmation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('PASSENGER')")
    public void confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody ConfirmRequest request) {
        afterTrip.confirm(CurrentUser.id(jwt), id, request.travelled());
    }

    @GetMapping("/admin/disputed-trips")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Dispute> disputes() {
        return afterTrip.disputes();
    }
}
