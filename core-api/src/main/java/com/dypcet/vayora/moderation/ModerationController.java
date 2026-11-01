package com.dypcet.vayora.moderation;

import com.dypcet.vayora.identity.CurrentUser;
import com.dypcet.vayora.moderation.ModerationService.Outcome;
import com.dypcet.vayora.moderation.ModerationService.Reason;
import com.dypcet.vayora.moderation.ModerationService.Report;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
public class ModerationController {

    public record FileReportRequest(@NotNull Long reportedUserId, Long bookingId,
            @NotNull Reason reason, @Size(max = 1000) String details) {
    }

    public record ResolveRequest(@NotNull Outcome outcome, @Size(max = 1000) String note) {
    }

    private final ModerationService moderation;

    public ModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('PASSENGER', 'DRIVER')")
    public Map<String, Long> file(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody FileReportRequest request) {
        return Map.of("id", moderation.file(CurrentUser.id(jwt), request.reportedUserId(),
                request.bookingId(), request.reason(), request.details()));
    }

    @GetMapping("/admin/reports")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Report> queue(
            @RequestParam(defaultValue = "OPEN") @Pattern(regexp = "OPEN|RESOLVED|DISMISSED") String status) {
        return moderation.withStatus(status);
    }

    @PostMapping("/admin/reports/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public Report resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody ResolveRequest request) {
        return moderation.resolve(CurrentUser.id(jwt), id, request.outcome(), request.note());
    }

    @PostMapping("/admin/users/{id}/reinstate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void reinstate(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        moderation.reinstate(CurrentUser.id(jwt), id);
    }
}
