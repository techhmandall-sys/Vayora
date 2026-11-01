package com.dypcet.vayora.ride;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-sided match scoring (feature 76): how well a ride suits the passenger, and how well
 * the two people's stated preferences agree. Out of 100, with the reasons that moved it.
 *
 * <p>Only what both people said on their profiles, the trip's distance and price, and the
 * driver's record of rides kept are used: never the durability model, and never anything
 * predicted about either person.
 */
public final class MatchScore {

    /** What a result's score says about it, for the app to put in words. */
    public enum Reason {
        NEAR,
        CHEAPEST,
        SAME_SMOKING,
        DIFFERENT_SMOKING,
        SAME_PETS,
        DIFFERENT_PETS,
        SAME_MUSIC,
        DIFFERENT_MUSIC,
        SAME_CONVERSATION,
        DIFFERENT_CONVERSATION
    }

    /** What someone said on their profile; null means not said. */
    public record Preferences(Boolean smoking, Boolean pets, Boolean music, String chattiness) {
        public static final Preferences NONE = new Preferences(null, null, null, null);
    }

    /**
     * How much each part counts. They add up to 100 so the score reads as a percentage.
     *
     * @param distance   pickup and drop-off near the passenger
     * @param price      the price against the cheapest result
     * @param record     the driver's rides completed against withdrawn
     * @param preference each of smoking, pets, music and conversation
     */
    public record Weights(int distance, int price, int record, int preference) {
        public Weights {
            if (distance < 0 || price < 0 || record < 0 || preference < 0
                    || distance + price + record + 4 * preference != 100) {
                throw new IllegalArgumentException("vayora.search.match weights must be positive and add up to 100");
            }
        }
    }

    public record Match(int score, List<Reason> reasons) {
    }

    // A part nothing is known about counts half, so silence neither helps nor hurts.
    private static final double UNKNOWN = 0.5;
    // Pickup and drop-off this close to the passenger's start and end, together, are "near".
    private static final double NEAR_SHARE = 0.8;
    private static final List<String> CHATTINESS = List.of("QUIET", "MODERATE", "TALKATIVE");

    private MatchScore() {
    }

    /**
     * @param accessMetres pickup plus drop-off distance, in a straight line
     * @param maxAccess    the most either may be, so twice it is the worst possible
     * @param price        per seat, for the passenger's stretch
     * @param cheapest     the lowest such price among the results
     */
    public static Match of(Preferences passenger, Preferences driver, long accessMetres, double maxAccess,
            BigDecimal price, BigDecimal cheapest, int ridesCompleted, int ridesWithdrawn, Weights weights) {
        List<Reason> reasons = new ArrayList<>();
        double total = 0;

        double near = 1 - Math.min(1, accessMetres / (2 * maxAccess));
        total += weights.distance() * near;
        if (near >= NEAR_SHARE) {
            reasons.add(Reason.NEAR);
        }

        double value = price.signum() <= 0 ? 1
                : cheapest.divide(price, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE).doubleValue();
        total += weights.price() * value;
        if (price.compareTo(cheapest) <= 0) {
            reasons.add(Reason.CHEAPEST);
        }

        int finished = ridesCompleted + ridesWithdrawn;
        total += weights.record() * (finished == 0 ? UNKNOWN : (double) ridesCompleted / finished);

        total += weights.preference() * agreement(passenger.smoking(), driver.smoking(),
                Reason.SAME_SMOKING, Reason.DIFFERENT_SMOKING, reasons);
        total += weights.preference() * agreement(passenger.pets(), driver.pets(),
                Reason.SAME_PETS, Reason.DIFFERENT_PETS, reasons);
        total += weights.preference() * agreement(passenger.music(), driver.music(),
                Reason.SAME_MUSIC, Reason.DIFFERENT_MUSIC, reasons);
        total += weights.preference() * conversation(passenger.chattiness(), driver.chattiness(), reasons);

        return new Match((int) Math.round(total), List.copyOf(reasons));
    }

    /** 1 when both said the same, 0 when both said and differ, half when either did not say. */
    private static double agreement(Boolean passenger, Boolean driver, Reason same, Reason different,
            List<Reason> reasons) {
        if (passenger == null || driver == null) {
            return UNKNOWN;
        }
        reasons.add(passenger.equals(driver) ? same : different);
        return passenger.equals(driver) ? 1 : 0;
    }

    /** The same: 1; a quiet ride against a talker: 0; one step apart: half, and no reason given. */
    private static double conversation(String passenger, String driver, List<Reason> reasons) {
        int a = passenger == null ? -1 : CHATTINESS.indexOf(passenger);
        int b = driver == null ? -1 : CHATTINESS.indexOf(driver);
        if (a < 0 || b < 0) {
            return UNKNOWN;
        }
        int apart = Math.abs(a - b);
        if (apart == 0) {
            reasons.add(Reason.SAME_CONVERSATION);
            return 1;
        }
        if (apart == 2) {
            reasons.add(Reason.DIFFERENT_CONVERSATION);
            return 0;
        }
        return UNKNOWN;
    }
}
