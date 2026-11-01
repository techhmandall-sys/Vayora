package com.dypcet.vayora.hazard;

import java.util.Map;
import java.util.Optional;

/** Estimates how likely a booking is to last. Implemented by a call to the intel service. */
public interface HazardScorer {

    /**
     * @param survivalProbability estimate that the passenger will not cancel before the
     *                            horizon, given no cancellation so far
     * @param contributions       each feature's share of the log relative risk; positive
     *                            raises the estimated chance of a cancellation. Empty if
     *                            the model gave none
     */
    record Score(double survivalProbability, String modelVersion, String featureVersion,
            Map<String, Double> contributions) {

        public Score(double survivalProbability, String modelVersion, String featureVersion) {
            this(survivalProbability, modelVersion, featureVersion, Map.of());
        }
    }

    /**
     * Both times are hours since the booking was accepted. Returns empty when no estimate
     * could be obtained; the caller must carry on without one.
     */
    Optional<Score> score(Map<String, Double> features, double elapsedHours, double horizonHours);
}
