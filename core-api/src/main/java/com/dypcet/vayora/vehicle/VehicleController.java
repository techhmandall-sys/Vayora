package com.dypcet.vayora.vehicle;

import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.vehicle.VehicleService.Comfort;
import com.dypcet.vayora.vehicle.VehicleService.Details;
import com.dypcet.vayora.vehicle.VehicleService.Luggage;
import com.dypcet.vayora.vehicle.VehicleService.Vehicle;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vehicles")
@PreAuthorize("hasRole('DRIVER')")
public class VehicleController {

    public record CreateVehicleRequest(
            @NotBlank @Size(max = 50) String make,
            @NotBlank @Size(max = 50) String model,
            @Min(1980) @Max(2100) Integer year,
            @Size(max = 30) String colour,
            @Min(2) @Max(9) int seats,
            Comfort comfort,
            Luggage luggage,
            boolean electric,
            @DecimalMin("1") @DecimalMax("300") @Digits(integer = 3, fraction = 1) BigDecimal batteryKwh,
            @DecimalMin("5") @DecimalMax("60") @Digits(integer = 2, fraction = 1)
            BigDecimal consumptionKwhPer100Km) {
    }

    /** Insurance as the driver declares it; never checked. */
    public record InsuranceRequest(@NotBlank @Size(max = 60) String insurer, @NotNull LocalDate insuredUntil) {
    }

    private final VehicleService vehicles;

    public VehicleController(VehicleService vehicles) {
        this.vehicles = vehicles;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Vehicle create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateVehicleRequest request) {
        return vehicles.create(CurrentUser.id(jwt), request.make(), request.model(),
                request.year(), request.colour(), request.seats(),
                new Details(request.comfort(), request.luggage(), request.electric(),
                        request.batteryKwh(), request.consumptionKwhPer100Km()));
    }

    @GetMapping
    public List<Vehicle> mine(@AuthenticationPrincipal Jwt jwt) {
        return vehicles.ownedBy(CurrentUser.id(jwt));
    }

    @PutMapping("/{id}/insurance")
    public Vehicle declareInsurance(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody InsuranceRequest request) {
        return vehicles.declareInsurance(id, CurrentUser.id(jwt), request.insurer(), request.insuredUntil());
    }

    @DeleteMapping("/{id}/insurance")
    public Vehicle clearInsurance(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return vehicles.clearInsurance(id, CurrentUser.id(jwt));
    }
}
