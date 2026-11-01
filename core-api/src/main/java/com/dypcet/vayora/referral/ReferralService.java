package com.dypcet.vayora.referral;

import com.dypcet.vayora.notification.NotificationService;
import com.dypcet.vayora.notification.NotificationService.Type;
import com.dypcet.vayora.notification.Text;
import com.dypcet.vayora.pass.PassProperties.Kind;
import com.dypcet.vayora.pass.PassService;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who brought whom. A member shares a code; someone who signs up with it is recorded as
 * referred, and counts as qualified once they complete a first trip, as passenger or driver.
 *
 * <p>Once qualified, each side of the referral who is a passenger gets a free pass of the
 * kind set in {@code vayora.referral.reward-pass} (feature 72), or nothing if that is NONE.
 * The pass is the platform giving up its own fee; no driver's fare is touched.
 */
@Service
public class ReferralService {

    /**
     * @param qualified  of those signed up, how many have completed a trip
     * @param rewardPass the pass each passenger of a qualified referral gets; null if none
     */
    public record Summary(String code, int signedUp, int qualified, Kind rewardPass) {
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I
    private static final int CODE_LENGTH = 6;

    private final JdbcTemplate jdbc;
    private final PassService passes;
    private final NotificationService notifications;
    private final Kind rewardPass;
    private final SecureRandom random = new SecureRandom();

    public ReferralService(JdbcTemplate jdbc, PassService passes, NotificationService notifications,
            @Value("${vayora.referral.reward-pass}") String rewardPass) {
        this.jdbc = jdbc;
        this.passes = passes;
        this.notifications = notifications;
        this.rewardPass = "NONE".equals(rewardPass) ? null : Kind.valueOf(rewardPass);
    }

    /** The member's code, made the first time it is asked for. */
    public String codeFor(long userId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String existing = jdbc.queryForObject("SELECT referral_code FROM users WHERE id = ?", String.class, userId);
            if (existing != null) {
                return existing;
            }
            try {
                jdbc.update("UPDATE users SET referral_code = ? WHERE id = ? AND referral_code IS NULL",
                        newCode(), userId);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Another member already has that code: try another.
            }
        }
        throw new IllegalStateException("Could not make a unique referral code");
    }

    /** The member a code belongs to. 400 if it belongs to nobody. */
    public long referrerOf(String code) {
        List<Long> found = jdbc.queryForList("SELECT id FROM users WHERE referral_code = ?", Long.class,
                code.strip().toUpperCase(Locale.ROOT));
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That referral code does not exist");
        }
        return found.get(0);
    }

    public void recordSignUp(long referrerId, long refereeId) {
        jdbc.update("INSERT INTO referrals (referrer_id, referee_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                referrerId, refereeId);
    }

    /**
     * Marks as qualified any of these members who were referred and had not yet completed a
     * trip, and rewards each such referral once: a free pass for each side who is a
     * passenger. A driver gets none, as a pass only waives the fee a passenger pays.
     */
    public void qualify(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        List<long[]> qualified = jdbc.query("""
                UPDATE referrals SET qualified_at = now()
                WHERE qualified_at IS NULL AND referee_id = ANY (?)
                RETURNING referrer_id, referee_id""",
                (rs, n) -> new long[] {rs.getLong("referrer_id"), rs.getLong("referee_id")},
                (Object) userIds.toArray(Long[]::new));
        if (rewardPass == null) {
            return;
        }
        for (long[] referral : qualified) {
            long referrer = referral[0];
            long referee = referral[1];
            if (isPassenger(referrer) && passes.reward(referrer, rewardPass, referee)) {
                notifications.send(referrer, Type.REFERRAL_REWARD, Text.of(
                        "{name} joined with your invitation and completed a first trip, so you have a free "
                                + "travel pass. It waives the service fee while it runs: see Passes.",
                        "name", nameOf(referee)), null);
            }
            if (isPassenger(referee) && passes.reward(referee, rewardPass, referee)) {
                notifications.send(referee, Type.REFERRAL_REWARD, Text.of(
                        "You completed your first trip after joining with an invitation, so you have a free "
                                + "travel pass. It waives the service fee while it runs: see Passes."), null);
            }
        }
    }

    /** @return also the pass a referral earns, or null when none is given */
    public Summary summary(long userId) {
        String code = codeFor(userId);
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS signed_up, COUNT(qualified_at) AS qualified
                FROM referrals WHERE referrer_id = ?""",
                (rs, n) -> new Summary(code, rs.getInt("signed_up"), rs.getInt("qualified"), rewardPass), userId);
    }

    private boolean isPassenger(long userId) {
        return "PASSENGER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId));
    }

    private String nameOf(long userId) {
        return jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, userId);
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
