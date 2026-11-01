package com.dypcet.vayora.booking;

import com.dypcet.vayora.booking.BoardingService.BoardingPass;
import com.dypcet.vayora.booking.BookingService.Booking;
import com.dypcet.vayora.booking.BookingService.RequestTerms;
import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.payment.PaymentChoice;
import com.dypcet.vayora.routing.RouteService.Point;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class BookingController {

    public record CreateBookingRequest(
            @NotNull Long rideId,
            @Min(0) int fromSeq,
            @Min(1) int toSeq,
            @Min(1) @Max(8) int seats,
            // How to pay; may be left out when a checkout has paid, as Razorpay says how.
            PaymentChoice.Method paymentMethod,
            // Mock provider only: "DECLINE" makes the payment fail.
            @Size(max = 20) String mockOutcome,
            // What Razorpay's checkout handed back once the passenger paid (feature 46).
            @Valid PaymentChoice.Checkout razorpay,
            // What happens if the driver withdraws; left out, this ride only.
            FlexTier tier,
            // To be picked up at this point rather than at stop fromSeq (feature 28); both or neither.
            @DecimalMin("-90") @DecimalMax("90") Double pickupLat,
            @DecimalMin("-180") @DecimalMax("180") Double pickupLon,
            // The last-mile gap and access option shown on the candidate booked (feature 91);
            // null when the client sent none. Both or neither.
            @Min(0) Integer lastMileDistanceM,
            @Pattern(regexp = "walk|rickshaw|bus") String lastMileModeShown,
            // Pay once the booking ends, on a ride whose driver accepts that; then nothing above
            // about paying is needed.
            boolean payAfterRide) {
    }

    /** How a passenger pays what a pay-after-ride booking owes: as when booking, or a checkout. */
    public record PayDueRequest(
            PaymentChoice.Method paymentMethod,
            @Size(max = 20) String mockOutcome,
            @Valid PaymentChoice.Checkout razorpay) {
    }

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    public record CodeCheck(@NotBlank @Pattern(regexp = "^[0-9]{4}$") String code) {
    }

    public record CodeResult(boolean matches) {
    }

    private final BookingService bookings;
    private final BoardingService boarding;

    public BookingController(BookingService bookings, BoardingService boarding) {
        this.bookings = bookings;
        this.boarding = boarding;
    }

    /** The passenger's own reference and boarding code. Never shown to the driver. */
    @GetMapping("/bookings/{id}/boarding-pass")
    @PreAuthorize("hasRole('PASSENGER')")
    public BoardingPass boardingPass(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return boarding.pass(CurrentUser.id(jwt), id);
    }

    /** Whether the code a passenger showed belongs to this booking. Changes nothing. */
    @PostMapping("/bookings/{id}/check-code")
    @PreAuthorize("hasRole('DRIVER')")
    public CodeResult checkCode(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody CodeCheck request) {
        return new CodeResult(boarding.check(CurrentUser.id(jwt), id, request.code()));
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PASSENGER')")
    public Booking request(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        if (idempotencyKey != null && (idempotencyKey.isBlank()
                || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return bookings.request(CurrentUser.id(jwt), request.rideId(), request.fromSeq(),
                request.toSeq(), request.seats(), request.payAfterRide() ? null
                        : PaymentChoice.of(request.paymentMethod(), request.mockOutcome(), request.razorpay()),
                idempotencyKey,
                request.tier() == null ? FlexTier.EXACT : request.tier(),
                request.pickupLat() == null || request.pickupLon() == null ? null
                        : new Point(request.pickupLat(), request.pickupLon()),
                request.lastMileDistanceM(), request.lastMileModeShown(), request.payAfterRide());
    }

    /** The passenger pays, in the app, what their pay-after-ride booking owes. */
    @PostMapping("/bookings/{id}/pay")
    @PreAuthorize("hasRole('PASSENGER')")
    public Booking pay(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody PayDueRequest request) {
        return bookings.payDue(CurrentUser.id(jwt), id,
                PaymentChoice.of(request.paymentMethod(), request.mockOutcome(), request.razorpay()));
    }

    /** The driver says the passenger paid what their pay-after-ride booking owes, in cash. */
    @PostMapping("/bookings/{id}/cash-received")
    @PreAuthorize("hasRole('DRIVER')")
    public Booking cashReceived(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return bookings.paidInCash(CurrentUser.id(jwt), id);
    }

    /** How long a driver has to answer a request. The same for everyone, so open to all. */
    @GetMapping("/booking-terms")
    public RequestTerms terms() {
        return bookings.requestTerms();
    }

    @GetMapping("/bookings")
    @PreAuthorize("hasRole('PASSENGER')")
    public List<Booking> mine(@AuthenticationPrincipal Jwt jwt) {
        return bookings.forPassenger(CurrentUser.id(jwt));
    }

    @PostMapping("/bookings/{id}/cancel")
    @PreAuthorize("hasRole('PASSENGER')")
    public Booking cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return bookings.cancel(CurrentUser.id(jwt), id);
    }

    @PostMapping("/bookings/{id}/accept")
    @PreAuthorize("hasRole('DRIVER')")
    public Booking accept(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return bookings.accept(CurrentUser.id(jwt), id);
    }

    @PostMapping("/bookings/{id}/reject")
    @PreAuthorize("hasRole('DRIVER')")
    public Booking reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return bookings.reject(CurrentUser.id(jwt), id);
    }

    @GetMapping("/rides/{rideId}/bookings")
    @PreAuthorize("hasRole('DRIVER')")
    public List<Booking> forRide(@AuthenticationPrincipal Jwt jwt, @PathVariable long rideId) {
        return bookings.forRide(CurrentUser.id(jwt), rideId);
    }
}
