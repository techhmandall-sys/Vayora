package com.dypcet.vayora.pass;

import com.dypcet.vayora.pass.PassProperties.Kind;
import com.dypcet.vayora.pass.PassProperties.Offer;
import com.dypcet.vayora.payment.PaymentChoice;
import com.dypcet.vayora.payment.PaymentProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Travel passes. While a passenger's pass lasts, bookings they request carry no service
 * fee. The driver is paid the same fare either way.
 */
@Service
@EnableConfigurationProperties(PassProperties.class)
public class PassService {

    /**
     * @param price  what was charged for it, through the provider named; nothing for a reward
     * @param reward given free for a referral (feature 72), not bought
     */
    public record Pass(long id, Kind kind, BigDecimal price, Instant startsAt, Instant endsAt,
            String provider, boolean reward) {
    }

    /** The provider recorded on a pass given as a referral reward: no payment behind it. */
    public static final String REFERRAL = "REFERRAL";

    private final JdbcTemplate jdbc;
    private final PaymentProvider provider;
    private final PassProperties properties;
    private final Clock clock;

    public PassService(JdbcTemplate jdbc, PaymentProvider provider, PassProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Offer> offers() {
        return properties.offers();
    }

    /**
     * Buys a pass. One bought while another is still running starts when that one ends,
     * so buying early never wastes days.
     *
     * @throws ResponseStatusException 402 if the payment is declined, and nothing is bought
     */
    @Transactional
    public Pass buy(long passengerId, Kind kind, PaymentChoice choice) {
        Offer offer = properties.offers().stream().filter(o -> o.kind() == kind).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That pass is not on sale"));
        // One purchase at a time per passenger, so two cannot both start at the same moment.
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, passengerId);
        Instant start = nextStart(passengerId);
        Instant end = start.plus(Duration.ofDays(offer.days()));

        PaymentProvider.Hold hold = provider.hold(offer.price(), choice).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "The payment was declined. No pass was bought."));
        provider.capture(hold.reference(), offer.price());
        Long id = jdbc.queryForObject("""
                INSERT INTO passes (passenger_id, kind, price, starts_at, ends_at, method, provider,
                                    provider_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, passengerId, kind.name(), offer.price(), utc(start), utc(end),
                hold.method().name(), provider.name(), hold.reference());
        return new Pass(id, kind, offer.price(), start, end, provider.name(), false);
    }

    /**
     * Gives a pass free, as a referral reward: once per passenger per referral, however often
     * it is asked for. Like a bought one, it starts when any pass still running ends.
     *
     * @param refereeId the invited member whose first trip earned it
     * @return whether a pass was given this time
     */
    @Transactional
    public boolean reward(long passengerId, Kind kind, long refereeId) {
        Offer offer = properties.offers().stream().filter(o -> o.kind() == kind).findFirst()
                .orElseThrow(() -> new IllegalStateException("No pass of kind " + kind + " is defined"));
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, passengerId);
        Instant start = nextStart(passengerId);
        return jdbc.update("""
                INSERT INTO passes (passenger_id, kind, price, starts_at, ends_at, provider, reward_for_referee)
                VALUES (?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT (passenger_id, reward_for_referee) WHERE reward_for_referee IS NOT NULL
                DO NOTHING""",
                passengerId, kind.name(), utc(start), utc(start.plus(Duration.ofDays(offer.days()))),
                REFERRAL, refereeId) == 1;
    }

    /** Now, or when the passenger's last pass ends if that is later. The caller holds the user lock. */
    private Instant nextStart(long passengerId) {
        // To the microsecond, which is what PostgreSQL keeps: the pass handed back is the pass
        // read back later.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime latest = jdbc.queryForObject(
                "SELECT MAX(ends_at) FROM passes WHERE passenger_id = ?", OffsetDateTime.class, passengerId);
        return latest == null || !latest.toInstant().isAfter(now) ? now : latest.toInstant();
    }

    /** The passenger's passes, latest first: running and future ones, then past ones. */
    public List<Pass> of(long passengerId) {
        return jdbc.query("""
                SELECT id, kind, price, starts_at, ends_at, provider,
                       reward_for_referee IS NOT NULL AS reward FROM passes
                WHERE passenger_id = ? ORDER BY ends_at DESC, id DESC""",
                (rs, n) -> new Pass(rs.getLong("id"), Kind.valueOf(rs.getString("kind")),
                        rs.getBigDecimal("price"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                        rs.getString("provider"), rs.getBoolean("reward")),
                passengerId);
    }

    /** Whether a pass of the passenger's covers this moment. */
    public boolean covers(long passengerId, Instant at) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM passes
                               WHERE passenger_id = ? AND starts_at <= ? AND ends_at > ?)""",
                Boolean.class, passengerId, utc(at), utc(at)));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
