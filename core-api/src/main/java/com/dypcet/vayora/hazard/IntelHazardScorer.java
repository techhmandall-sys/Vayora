package com.dypcet.vayora.hazard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks the intel service for a score. Any failure, slow answer or nonsense answer is an
 * empty result: a model outage must never break a booking or a batch.
 */
@Component
public class IntelHazardScorer implements HazardScorer {

    private static final Logger log = LoggerFactory.getLogger(IntelHazardScorer.class);

    record Request(
            Map<String, Double> features,
            @JsonProperty("elapsed_hours") double elapsedHours,
            @JsonProperty("horizon_hours") double horizonHours) {
    }

    record Response(
            @JsonProperty("survival_probability") Double survivalProbability,
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("feature_version") String featureVersion,
            @JsonProperty("contributions") Map<String, Double> contributions) {
    }

    private final RestClient client;

    @Autowired
    public IntelHazardScorer(RestClient.Builder builder,
            @Value("${vayora.intel.url}") String baseUrl,
            @Value("${vayora.intel.timeout}") Duration timeout) {
        this(builder.baseUrl(baseUrl).requestFactory(timeouts(timeout)).build());
    }

    IntelHazardScorer(RestClient client) {
        this.client = client;
    }

    private static SimpleClientHttpRequestFactory timeouts(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }

    @Override
    public Optional<Score> score(Map<String, Double> features, double elapsedHours,
            double horizonHours) {
        try {
            Response response = client.post().uri("/v1/hazard/score")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(features, elapsedHours, horizonHours))
                    .retrieve()
                    .body(Response.class);
            if (response == null || response.survivalProbability() == null
                    || response.survivalProbability() < 0 || response.survivalProbability() > 1
                    || !HazardFeatures.VERSION.equals(response.featureVersion())) {
                log.warn("Unusable hazard score from intel: {}", response);
                return Optional.empty();
            }
            return Optional.of(new Score(response.survivalProbability(), response.modelVersion(),
                    response.featureVersion(),
                    response.contributions() == null ? Map.of() : Map.copyOf(response.contributions())));
        } catch (RestClientException e) {
            log.warn("Hazard score unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
