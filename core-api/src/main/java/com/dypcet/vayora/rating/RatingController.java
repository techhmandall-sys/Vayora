package com.dypcet.vayora.rating;

import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.rating.RatingService.Profile;
import com.dypcet.vayora.rating.RatingService.Unrated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
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
public class RatingController {

    public record RateRequest(@Min(1) @Max(5) int stars, @Size(max = 1000) String comment) {
    }

    private final RatingService ratings;

    public RatingController(RatingService ratings) {
        this.ratings = ratings;
    }

    @PostMapping("/bookings/{bookingId}/rating")
    @ResponseStatus(HttpStatus.CREATED)
    public void rate(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookingId,
            @Valid @RequestBody RateRequest request) {
        ratings.rate(CurrentUser.id(jwt), bookingId, request.stars(), request.comment());
    }

    @GetMapping("/ratings/pending")
    public List<Unrated> pending(@AuthenticationPrincipal Jwt jwt) {
        return ratings.unratedBy(CurrentUser.id(jwt));
    }

    /** The signed-in driver's own record. */
    @GetMapping("/me/reliability")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('DRIVER')")
    public RatingService.Reliability reliability(@AuthenticationPrincipal Jwt jwt) {
        return ratings.reliability(CurrentUser.id(jwt));
    }

    @GetMapping("/users/{id}/profile")
    public Profile profile(@PathVariable long id) {
        return ratings.profile(id);
    }
}
