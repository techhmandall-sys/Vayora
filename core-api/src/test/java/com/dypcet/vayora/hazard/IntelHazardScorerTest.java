package com.dypcet.vayora.hazard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dypcet.vayora.hazard.HazardScorer.Score;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class IntelHazardScorerTest {

    private static final Map<String, Double> FEATURES = Map.of("lead_hours", 48.0);

    private MockRestServiceServer intel;
    private IntelHazardScorer scorer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://intel");
        intel = MockRestServiceServer.bindTo(builder).build();
        scorer = new IntelHazardScorer(builder.build());
    }

    private void intelAnswers(String json) {
        intel.expect(requestTo("http://intel/v1/hazard/score"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void sendsTheContractIntelExpectsAndReadsTheScore() {
        intel.expect(requestTo("http://intel/v1/hazard/score"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"features": {"lead_hours": 48.0}, "elapsed_hours": 6.0, "horizon_hours": 54.0}
                        """))
                .andRespond(withSuccess("""
                        {"survival_probability": 0.87, "model_version": "h3-cox", "contributions": {"lead_hours": 0.25},
                         "feature_version": "f2", "model_kind": "cox_ph", "trained_on": "synthetic",
                         "elapsed_hours": 6.0, "horizon_hours": 54.0,
                         "scored_at": "2026-10-10T06:00:00Z"}""", MediaType.APPLICATION_JSON));

        Optional<Score> score = scorer.score(FEATURES, 6.0, 54.0);

        assertThat(score).contains(new Score(0.87, "h3-cox", "f2", java.util.Map.of("lead_hours", 0.25)));
        intel.verify();
    }

    @Test
    void anOutageIsAnEmptyScoreNotAnException() {
        intel.expect(requestTo("http://intel/v1/hazard/score")).andRespond(withServerError());

        assertThat(scorer.score(FEATURES, 0, 10)).isEmpty();
    }

    @Test
    void aScoreForOtherFeaturesIsRefused() {
        intelAnswers("""
                {"survival_probability": 0.87, "model_version": "h2", "feature_version": "f3"}""");

        assertThat(scorer.score(FEATURES, 0, 10)).isEmpty();
    }

    @Test
    void aProbabilityOutsideZeroToOneOrMissingIsRefused() {
        intelAnswers("""
                {"survival_probability": 1.4, "model_version": "h3-cox", "feature_version": "f2"}""");
        assertThat(scorer.score(FEATURES, 0, 10)).isEmpty();

        intel.reset();
        intelAnswers("""
                {"model_version": "h3-cox", "feature_version": "f2"}""");
        assertThat(scorer.score(FEATURES, 0, 10)).isEmpty();
    }
}
