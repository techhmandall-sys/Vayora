package com.dypcet.vayora.bus;

import com.dypcet.vayora.bus.BusService.Bus;
import com.dypcet.vayora.bus.BusService.NewBus;
import com.dypcet.vayora.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BusController {

    public record BusRequest(@NotBlank @Size(max = 100) String operator, @NotBlank @Size(max = 100) String fromStop,
            @NotBlank @Size(max = 100) String toStop, @NotNull LocalTime departs,
            @Min(10) @Max(1440) int durationMinutes, @NotNull @DecimalMin("0") BigDecimal fare,
            @Size(max = 300) String notes) {
    }

    private final BusService buses;

    public BusController(BusService buses) {
        this.buses = buses;
    }

    /** Buses from one town to another, leaving at or after a time of day. */
    @GetMapping("/bus-alternatives")
    public List<Bus> alternatives(@RequestParam String from, @RequestParam String to,
            @RequestParam(defaultValue = "00:00") @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime after) {
        return buses.between(from, to, after, 5);
    }

    @GetMapping("/admin/bus-services")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Bus> all() {
        return buses.all();
    }

    @PostMapping("/admin/bus-services")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public Bus add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BusRequest request) {
        return buses.add(CurrentUser.id(jwt), new NewBus(request.operator(), request.fromStop(), request.toStop(),
                request.departs(), request.durationMinutes(), request.fare(), request.notes()));
    }

    @DeleteMapping("/admin/bus-services/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        buses.remove(CurrentUser.id(jwt), id);
    }
}
