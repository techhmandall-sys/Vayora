package com.dypcet.vayora.check;

import com.dypcet.vayora.check.DocumentCheckService.Check;
import com.dypcet.vayora.check.DocumentCheckService.Kind;
import com.dypcet.vayora.check.DocumentCheckService.Outcome;
import com.dypcet.vayora.check.DocumentCheckService.Waiting;
import com.dypcet.vayora.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DocumentCheckController {

    /** @param vehicleId the car, for insurance */
    public record AskRequest(@NotNull Kind kind, Long vehicleId) {
    }

    /** @param note why it could not be confirmed; needed for a refusal, and shown to the driver */
    public record DecideRequest(@NotNull Outcome outcome, @Size(max = 500) String note) {
    }

    private final DocumentCheckService checks;

    public DocumentCheckController(DocumentCheckService checks) {
        this.checks = checks;
    }

    @PostMapping("/me/document-checks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DRIVER')")
    public Check ask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AskRequest request) {
        return checks.request(CurrentUser.id(jwt), request.kind(), request.vehicleId());
    }

    @GetMapping("/me/document-checks")
    @PreAuthorize("hasRole('DRIVER')")
    public List<Check> mine(@AuthenticationPrincipal Jwt jwt) {
        return checks.of(CurrentUser.id(jwt));
    }

    @GetMapping("/admin/document-checks")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Waiting> waiting() {
        return checks.waiting();
    }

    @PostMapping("/admin/document-checks/{id}/decide")
    @PreAuthorize("hasRole('ADMIN')")
    public Check decide(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody DecideRequest request) {
        return checks.decide(CurrentUser.id(jwt), id, request.outcome(), request.note());
    }
}
