package com.dypcet.vayora.rating;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@EnableConfigurationProperties(LevelProperties.class)
public class RatingService {

    /** @param reviewerName the first name only of whoever wrote it */
    public record Review(int stars, String comment, String reviewerName, Instant createdAt) {
    }

    /**
     * What anyone signed in may know about a member. No contact details.
     *
     * @param completedTrips trips travelled as a passenger or driven as a driver
     * @param averageStars   null until somebody has rated them
     * @param reviews        the ten newest ratings that came with a comment
     * @param emailVerified  they proved the address with a code; says nothing about who they are
     * @param age            from the birth year they gave, so a year out either way; null if not said
     * @param smoking        what they said about travelling; null, like the next three, if not said
     * @param responseRate   of the requests a driver had to answer, the share they answered
     *                       themselves; null for a passenger or until there are enough
     * @param responseMinutes typical (median) minutes from a request to the driver's answer;
     *                        null as above
     * @param idCheckedAt    when VAYORA staff saw that document, if they have: a person saw it,
     *                       not its issuer; null if not
     * @param declaredId     the kind of identity document a driver says they hold; declared,
     *                       never checked, and counted towards nothing; null if not said
     */
    public record Profile(long id, String fullName, String role, Instant memberSince,
            int completedTrips, Double averageStars, int ratingCount, List<Review> reviews,
            boolean emailVerified, boolean phoneVerified, String bio, Integer age,
            Boolean smoking, Boolean pets, Boolean music, String chattiness,
            Double responseRate, Integer responseMinutes, String level, String declaredId,
            Instant idCheckedAt) {
    }

    /**
     * A driver's own record, for them to see. Counts are over everything they have ever
     * driven.
     *
     * @param ridesWithdrawn       rides the driver cancelled before they ran
     * @param passengersCancelled  bookings the driver cancelled, by withdrawing or otherwise
     * @param noShowsMarked        passengers the driver marked as not boarding
     */
    public record Reliability(int ridesCompleted, int ridesWithdrawn, int passengersCarried,
            int passengersCancelled, int noShowsMarked, Double averageStars, int ratingCount,
            Double responseRate, Integer responseMinutes, String level) {
    }

    private record Responsiveness(Double rate, Integer minutes) {
    }

    /** A completed trip the caller has not rated yet. */
    public record Unrated(long bookingId, long otherPartyId, String otherPartyName, String fromStop,
            String toStop, Instant boardingTime) {
    }

    private record Parties(long passengerId, long driverId, String status) {
    }

    private static final int REVIEWS_SHOWN = 10;
    // Fewer than this and a rate would mostly be noise, so none is shown.
    private static final int MIN_REQUESTS_FOR_RESPONSE = 3;

    private final JdbcTemplate jdbc;

    private final LevelProperties levels;

    public RatingService(JdbcTemplate jdbc, LevelProperties levels) {
        this.jdbc = jdbc;
        this.levels = levels;
    }

    /** Rates the other party of a completed booking. Each side may do so once. */
    public void rate(long raterId, long bookingId, int stars, String comment) {
        List<Parties> found = jdbc.query("""
                SELECT b.passenger_id, r.driver_id, b.status
                FROM bookings b JOIN rides r ON r.id = b.ride_id WHERE b.id = ?""",
                (rs, n) -> new Parties(rs.getLong("passenger_id"), rs.getLong("driver_id"),
                        rs.getString("status")),
                bookingId);
        // A booking the caller had no part in looks the same as one that does not exist.
        if (found.isEmpty() || (found.get(0).passengerId() != raterId
                && found.get(0).driverId() != raterId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        }
        Parties parties = found.get(0);
        if (!"COMPLETED".equals(parties.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a completed trip can be rated");
        }
        long rateeId = parties.passengerId() == raterId ? parties.driverId() : parties.passengerId();
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        try {
            jdbc.update("""
                    INSERT INTO ratings (booking_id, rater_id, ratee_id, stars, comment)
                    VALUES (?, ?, ?, ?, ?)""",
                    bookingId, raterId, rateeId, stars, text);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already rated this trip");
        }
    }

    public Profile profile(long userId) {
        List<Review> reviews = jdbc.query("""
                SELECT g.stars, g.comment, split_part(u.full_name, ' ', 1) AS reviewer, g.created_at
                FROM ratings g JOIN users u ON u.id = g.rater_id
                WHERE g.ratee_id = ? AND g.comment IS NOT NULL
                ORDER BY g.id DESC LIMIT ?""",
                (rs, n) -> new Review(rs.getInt("stars"), rs.getString("comment"),
                        rs.getString("reviewer"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                userId, REVIEWS_SHOWN);
        List<Profile> found = jdbc.query("""
                SELECT u.id, u.full_name, u.role, u.created_at,
                       (SELECT COUNT(*) FROM bookings b JOIN rides r ON r.id = b.ride_id
                        WHERE b.status = 'COMPLETED'
                          AND (b.passenger_id = u.id OR r.driver_id = u.id)) AS completed,
                       (SELECT AVG(stars)::float8 FROM ratings WHERE ratee_id = u.id) AS average,
                       (SELECT COUNT(*) FROM ratings WHERE ratee_id = u.id) AS rating_count,
                       u.email_verified_at IS NOT NULL AS email_verified,
                       u.phone_verified_at IS NOT NULL AS phone_verified,
                       p.bio, EXTRACT(YEAR FROM now())::int - p.birth_year AS age,
                       p.smoking, p.pets, p.music, p.chattiness, u.id_document,
                       (SELECT c.decided_at FROM document_checks c
                        WHERE c.driver_id = u.id AND c.kind = 'IDENTITY' AND c.status = 'CHECKED') AS id_checked_at
                FROM users u LEFT JOIN profiles p ON p.user_id = u.id
                WHERE u.id = ? AND u.role <> 'ADMIN'""",
                (rs, n) -> new Profile(rs.getLong("id"), rs.getString("full_name"),
                        rs.getString("role"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("completed"), (Double) rs.getObject("average"),
                        rs.getInt("rating_count"), reviews,
                        rs.getBoolean("email_verified"), rs.getBoolean("phone_verified"),
                        rs.getString("bio"), (Integer) rs.getObject("age"),
                        (Boolean) rs.getObject("smoking"), (Boolean) rs.getObject("pets"),
                        (Boolean) rs.getObject("music"), rs.getString("chattiness"),
                        null, null,
                        levels.levelFor(rs.getInt("completed"), (Double) rs.getObject("average"),
                                rs.getInt("rating_count"), rs.getBoolean("email_verified"),
                                rs.getBoolean("phone_verified")),
                        rs.getString("id_document"), idCheckedAt(rs.getObject("id_checked_at", OffsetDateTime.class))),
                userId);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        }
        Profile profile = found.get(0);
        if (!"DRIVER".equals(profile.role())) {
            return profile;
        }
        Responsiveness answers = responsiveness(userId);
        return new Profile(profile.id(), profile.fullName(), profile.role(), profile.memberSince(),
                profile.completedTrips(), profile.averageStars(), profile.ratingCount(),
                profile.reviews(), profile.emailVerified(), profile.phoneVerified(), profile.bio(),
                profile.age(), profile.smoking(), profile.pets(), profile.music(),
                profile.chattiness(), answers.rate(), answers.minutes(), profile.level(),
                profile.declaredId(), profile.idCheckedAt());
    }

    private static Instant idCheckedAt(OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }

    /** The driver's own record. */
    public Reliability reliability(long driverId) {
        Profile profile = profile(driverId);
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM rides WHERE driver_id = ? AND status = 'COMPLETED') AS completed,
                  (SELECT COUNT(*) FROM rides WHERE driver_id = ? AND status = 'CANCELLED') AS withdrawn,
                  (SELECT COUNT(*) FROM bookings b JOIN rides r ON r.id = b.ride_id
                   WHERE r.driver_id = ? AND b.status = 'COMPLETED') AS carried,
                  (SELECT COUNT(*) FROM bookings b JOIN rides r ON r.id = b.ride_id
                   WHERE r.driver_id = ? AND b.status = 'CANCELLED' AND b.cancelled_by = 'driver') AS cancelled,
                  (SELECT COUNT(*) FROM bookings b JOIN rides r ON r.id = b.ride_id
                   WHERE r.driver_id = ? AND b.status = 'NO_SHOW') AS no_shows""",
                (rs, n) -> new Reliability(rs.getInt("completed"), rs.getInt("withdrawn"),
                        rs.getInt("carried"), rs.getInt("cancelled"), rs.getInt("no_shows"),
                        profile.averageStars(), profile.ratingCount(), profile.responseRate(),
                        profile.responseMinutes(), profile.level()),
                driverId, driverId, driverId, driverId, driverId);
    }

    /**
     * How a driver answers requests on rides that need approval. A request counts once it
     * is settled one way or the other: accepted, declined by the driver, or closed by the
     * platform because the driver did not answer. Requests the passenger withdrew while
     * waiting, and requests still waiting, do not count either way.
     */
    private Responsiveness responsiveness(long driverId) {
        return jdbc.queryForObject("""
                WITH asked AS (
                    SELECT b.created_at,
                           CASE WHEN b.accepted_at IS NOT NULL THEN b.accepted_at
                                WHEN COALESCE(b.rejected_by, 'driver') = 'driver' THEN b.updated_at
                           END AS answered_at
                    FROM bookings b JOIN rides r ON r.id = b.ride_id
                    WHERE r.driver_id = ? AND NOT r.instant_booking
                      AND (b.accepted_at IS NOT NULL OR b.status = 'REJECTED')
                )
                SELECT COUNT(*) AS asked,
                       1.0 * COUNT(answered_at) / NULLIF(COUNT(*), 0) AS rate,
                       percentile_cont(0.5) WITHIN GROUP (
                           ORDER BY EXTRACT(EPOCH FROM answered_at - created_at) / 60)
                           FILTER (WHERE answered_at IS NOT NULL) AS minutes
                FROM asked""",
                (rs, n) -> rs.getInt("asked") < MIN_REQUESTS_FOR_RESPONSE ? new Responsiveness(null, null)
                        : new Responsiveness(rs.getDouble("rate"),
                                rs.getObject("minutes") == null ? null
                                        : (int) Math.round(rs.getDouble("minutes"))),
                driverId);
    }

    /** The caller's completed trips still waiting for their rating, newest first. */
    public List<Unrated> unratedBy(long userId) {
        return jdbc.query("""
                SELECT b.id, o.id AS other_id, o.full_name AS other_name,
                       s.name AS from_stop, t.name AS to_stop,
                       r.departure_time + make_interval(mins => s.offset_minutes) AS boarding
                FROM bookings b
                JOIN rides r ON r.id = b.ride_id
                JOIN users o ON o.id = CASE WHEN b.passenger_id = ? THEN r.driver_id
                                            ELSE b.passenger_id END
                JOIN ride_stops s ON s.ride_id = b.ride_id AND s.seq = b.from_seq
                JOIN ride_stops t ON t.ride_id = b.ride_id AND t.seq = b.to_seq
                WHERE b.status = 'COMPLETED' AND (b.passenger_id = ? OR r.driver_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM ratings g
                                  WHERE g.booking_id = b.id AND g.rater_id = ?)
                ORDER BY b.id DESC""",
                (rs, n) -> new Unrated(rs.getLong("id"), rs.getLong("other_id"),
                        rs.getString("other_name"), rs.getString("from_stop"),
                        rs.getString("to_stop"),
                        rs.getObject("boarding", OffsetDateTime.class).toInstant()),
                userId, userId, userId, userId);
    }
}
