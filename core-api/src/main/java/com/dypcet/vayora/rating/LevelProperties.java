package com.dypcet.vayora.rating;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Experience levels, from the first to the highest. A member holds the highest level whose
 * every condition they meet. The thresholds are the authors' choices, not taken from any
 * other platform, and live in configuration so the team can change them.
 *
 * @param levels lowest first; the first must have no conditions, so everyone has a level
 */
@ConfigurationProperties("vayora.levels")
public record LevelProperties(List<Level> levels) {

    /**
     * @param minCompletedTrips trips travelled or driven to completion
     * @param minAverageStars   null for no condition on ratings
     * @param minRatings        ratings the average must be over
     * @param confirmedEmail    must have confirmed an email address
     * @param confirmedPhone    must have confirmed a phone number
     */
    public record Level(String name, int minCompletedTrips, Double minAverageStars, int minRatings,
            boolean confirmedEmail, boolean confirmedPhone) {
    }

    public LevelProperties {
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("vayora.levels.levels must not be empty");
        }
        Level first = levels.get(0);
        if (first.minCompletedTrips() != 0 || first.minAverageStars() != null || first.minRatings() != 0
                || first.confirmedEmail() || first.confirmedPhone()) {
            throw new IllegalArgumentException("vayora.levels: the first level must have no conditions");
        }
        levels = List.copyOf(levels);
    }

    /** The highest level these figures earn. */
    public String levelFor(int completedTrips, Double averageStars, int ratings, boolean emailConfirmed,
            boolean phoneConfirmed) {
        String earned = levels.get(0).name();
        for (Level level : levels) {
            boolean meets = completedTrips >= level.minCompletedTrips()
                    && ratings >= level.minRatings()
                    && (level.minAverageStars() == null
                            || (averageStars != null && averageStars >= level.minAverageStars()))
                    && (!level.confirmedEmail() || emailConfirmed)
                    && (!level.confirmedPhone() || phoneConfirmed);
            if (meets) {
                earned = level.name();
            }
        }
        return earned;
    }
}
