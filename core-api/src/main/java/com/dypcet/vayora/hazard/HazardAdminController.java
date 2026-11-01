package com.dypcet.vayora.hazard;

import com.dypcet.vayora.audit.AuditService;
import com.dypcet.vayora.hazard.HazardScoringService.LatestScore;
import com.dypcet.vayora.hazard.HazardScoringService.Summary;
import com.dypcet.vayora.identity.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrators only. A score is a weak, uncertain estimate about a person; showing it
 * to a driver would invite declining passengers on the strength of it.
 */
@RestController
@RequestMapping("/api/v1/admin/hazard")
@PreAuthorize("hasRole('ADMIN')")
public class HazardAdminController {

    private final HazardScoringService scoring;
    private final AuditService audit;

    public HazardAdminController(HazardScoringService scoring, AuditService audit) {
        this.scoring = scoring;
        this.audit = audit;
    }

    /** Safe to repeat: a booking already scored today is skipped. */
    @PostMapping("/score")
    public Summary scoreNow(@AuthenticationPrincipal Jwt jwt) {
        Summary summary = scoring.scoreActiveBookings();
        audit.record(CurrentUser.id(jwt), "HAZARD_SCORING_RUN", null, null,
                Map.of("scored", summary.scored(), "failed", summary.failed()));
        return summary;
    }

    @GetMapping("/scores")
    public List<LatestScore> scores() {
        return scoring.latestScores();
    }
}
