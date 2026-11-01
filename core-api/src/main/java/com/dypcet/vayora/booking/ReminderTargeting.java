package com.dypcet.vayora.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whom the seat-release reminder asks (features 79 and 80). Enabled, only bookings the
 * durability model last rated below the threshold, chosen within these limits by intel's
 * contact scheduler, as the simulator's B5 does. Disabled, everyone alike. The numbers are
 * the authors' assumptions.
 *
 * @param contactsPerRound     the most asked in one run of the reminder job
 * @param refillHorizonHours   a release this long before departure, or longer, counts in full
 */
@ConfigurationProperties("vayora.reminders.targeting")
public record ReminderTargeting(boolean enabled, double survivalThreshold, int contactsPerRound,
        int contactsPerRide, int seatsPerDepartureDay, double refillHorizonHours,
        double solverTimeLimitSeconds) {

    public ReminderTargeting {
        if (survivalThreshold < 0 || survivalThreshold > 1) {
            throw new IllegalArgumentException("vayora.reminders.targeting.survival-threshold must be 0 to 1");
        }
        if (Math.min(contactsPerRound, Math.min(contactsPerRide, seatsPerDepartureDay)) < 1
                || refillHorizonHours <= 0 || solverTimeLimitSeconds <= 0 || solverTimeLimitSeconds > 30) {
            throw new IllegalArgumentException("vayora.reminders.targeting limits must be positive, the solver's at most 30 s");
        }
    }
}
