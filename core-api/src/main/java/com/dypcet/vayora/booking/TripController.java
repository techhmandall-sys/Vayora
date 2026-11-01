package com.dypcet.vayora.booking;

import com.dypcet.vayora.booking.BookingService.Booking;
import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.ride.RideService.Ride;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasRole('DRIVER')")
public class TripController {

    private final TripService trips;

    public TripController(TripService trips) {
        this.trips = trips;
    }

    @PostMapping("/rides/{id}/start")
    public Ride start(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return trips.start(CurrentUser.id(jwt), id);
    }

    @PostMapping("/rides/{id}/complete")
    public Ride complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return trips.complete(CurrentUser.id(jwt), id);
    }

    @PostMapping("/rides/{id}/cancel")
    public Ride cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return trips.cancel(CurrentUser.id(jwt), id);
    }

    public record SeriesCancelled(int withdrawn) {
    }

    /** Withdraws every ride still to run in this ride's series. */
    @PostMapping("/rides/{id}/cancel-series")
    public SeriesCancelled cancelSeries(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return new SeriesCancelled(trips.cancelSeries(CurrentUser.id(jwt), id));
    }

    @PostMapping("/bookings/{id}/no-show")
    public Booking noShow(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return trips.noShow(CurrentUser.id(jwt), id);
    }
}
