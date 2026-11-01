package com.dypcet.vayora.stopover;

import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.stopover.StopoverService.Kind;
import com.dypcet.vayora.stopover.StopoverService.NewStopover;
import com.dypcet.vayora.stopover.StopoverService.Stopover;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/stopovers")
@PreAuthorize("hasRole('ADMIN')")
public class StopoverController {

    public record StopoverRequest(@NotNull Kind kind, @NotBlank @Size(max = 100) String name,
            @DecimalMin("-90") @DecimalMax("90") double lat, @DecimalMin("-180") @DecimalMax("180") double lon,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000") BigDecimal chargerKw,
            @Size(max = 300) String notes) {
    }

    private final StopoverService stopovers;

    public StopoverController(StopoverService stopovers) {
        this.stopovers = stopovers;
    }

    @GetMapping
    public List<Stopover> all() {
        return stopovers.all();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Stopover add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StopoverRequest request) {
        return stopovers.add(CurrentUser.id(jwt), new NewStopover(request.kind(), request.name(), request.lat(),
                request.lon(), request.chargerKw(), request.notes()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        stopovers.remove(CurrentUser.id(jwt), id);
    }
}
