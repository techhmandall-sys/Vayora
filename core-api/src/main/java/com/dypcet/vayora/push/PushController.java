package com.dypcet.vayora.push;

import com.dypcet.vayora.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PushController {

    /** What a browser's PushSubscription.toJSON() gives. */
    public record SubscriptionRequest(@NotBlank @Size(max = 2000) String endpoint, @NotNull @Valid Keys keys) {
    }

    public record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
    }

    /** @param publicKey null when push is not set up on this server */
    public record PushSettings(String publicKey, boolean subscribed) {
    }

    private final PushService push;

    public PushController(PushService push) {
        this.push = push;
    }

    @GetMapping("/me/push")
    public PushSettings settings(@AuthenticationPrincipal Jwt jwt) {
        return new PushSettings(push.publicKey(), push.subscribed(CurrentUser.id(jwt)));
    }

    @PostMapping("/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubscriptionRequest request) {
        push.subscribe(CurrentUser.id(jwt), request.endpoint(), request.keys().p256dh(), request.keys().auth());
    }

    @DeleteMapping("/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal Jwt jwt, @RequestParam String endpoint) {
        push.unsubscribe(CurrentUser.id(jwt), endpoint);
    }
}
