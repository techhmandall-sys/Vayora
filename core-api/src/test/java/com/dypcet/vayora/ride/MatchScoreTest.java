package com.dypcet.vayora.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dypcet.vayora.ride.MatchScore.Match;
import com.dypcet.vayora.ride.MatchScore.Preferences;
import com.dypcet.vayora.ride.MatchScore.Reason;
import com.dypcet.vayora.ride.MatchScore.Weights;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Feature 76: the score from both people's stated preferences, distance, price and record. */
class MatchScoreTest {

    private static final Weights WEIGHTS = new Weights(25, 20, 15, 10);
    private static final double MAX_ACCESS = 5000;
    private static final BigDecimal PRICE = new BigDecimal("400");

    private static Match score(Preferences passenger, Preferences driver) {
        return MatchScore.of(passenger, driver, 0, MAX_ACCESS, PRICE, PRICE, 10, 0, WEIGHTS);
    }

    @Test
    void agreeingOnEverythingAtTheDoorstepAtTheBestPriceIsAPerfectScore() {
        Preferences same = new Preferences(false, true, true, "QUIET");

        Match match = score(same, same);

        assertThat(match.score()).isEqualTo(100);
        assertThat(match.reasons()).containsExactly(Reason.NEAR, Reason.CHEAPEST, Reason.SAME_SMOKING,
                Reason.SAME_PETS, Reason.SAME_MUSIC, Reason.SAME_CONVERSATION);
    }

    @Test
    void disagreeingCostsEachPreferenceItsWholeWeightAndSaysWhere() {
        Match match = score(new Preferences(false, false, false, "QUIET"), new Preferences(true, true, true, "TALKATIVE"));

        assertThat(match.score()).isEqualTo(60); // 25 + 20 + 15, nothing from the four preferences
        assertThat(match.reasons()).contains(Reason.DIFFERENT_SMOKING, Reason.DIFFERENT_PETS,
                Reason.DIFFERENT_MUSIC, Reason.DIFFERENT_CONVERSATION);
    }

    @Test
    void whatEitherLeftUnsaidCountsHalfAndGivesNoReason() {
        Match unsaid = score(Preferences.NONE, new Preferences(true, true, true, "TALKATIVE"));

        assertThat(unsaid.score()).isEqualTo(80); // 60 + 4 x 5
        assertThat(unsaid.reasons()).containsExactly(Reason.NEAR, Reason.CHEAPEST);
    }

    @Test
    void conversationOneStepApartIsHalfWithoutAReason() {
        Match match = score(new Preferences(null, null, null, "QUIET"), new Preferences(null, null, null, "MODERATE"));

        assertThat(match.score()).isEqualTo(80);
        assertThat(match.reasons()).doesNotContain(Reason.SAME_CONVERSATION, Reason.DIFFERENT_CONVERSATION);
    }

    @Test
    void distancePriceAndRecordEachScaleTheirOwnPart() {
        Preferences none = Preferences.NONE;
        // Pickup and drop-off 5 km in all, out of a possible 10: half the distance part.
        assertThat(MatchScore.of(none, none, 5000, MAX_ACCESS, PRICE, PRICE, 10, 0, WEIGHTS).score())
                .isEqualTo(68); // 12.5 + 20 + 15 + 20, rounded
        // Twice the cheapest price: half the price part, and not the cheapest.
        Match dear = MatchScore.of(none, none, 0, MAX_ACCESS, new BigDecimal("800"), PRICE, 10, 0, WEIGHTS);
        assertThat(dear.score()).isEqualTo(70);
        assertThat(dear.reasons()).doesNotContain(Reason.CHEAPEST);
        // A driver who withdrew half their rides, and one with no record at all.
        assertThat(MatchScore.of(none, none, 0, MAX_ACCESS, PRICE, PRICE, 5, 5, WEIGHTS).score()).isEqualTo(73);
        assertThat(MatchScore.of(none, none, 0, MAX_ACCESS, PRICE, PRICE, 0, 0, WEIGHTS).score()).isEqualTo(73);
    }

    @Test
    void weightsThatDoNotAddUpToAHundredAreRefused() {
        assertThatThrownBy(() -> new Weights(30, 20, 15, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Weights(-5, 30, 35, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
