package com.dypcet.vayora.call;

import com.dypcet.vayora.call.CallService.Relay;
import com.dypcet.vayora.identity.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A simulated masked call between the two sides of a booking. */
@RestController
@RequestMapping("/api/v1/bookings")
@PreAuthorize("hasAnyRole('PASSENGER', 'DRIVER')")
public class CallController {

    private final CallService calls;

    public CallController(CallService calls) {
        this.calls = calls;
    }

    @PostMapping("/{id}/call")
    public Relay call(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return calls.call(CurrentUser.id(jwt), id);
    }
}
