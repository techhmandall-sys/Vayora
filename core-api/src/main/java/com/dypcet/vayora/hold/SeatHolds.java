package com.dypcet.vayora.hold;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Seats set aside for a passenger for a few minutes while they decide, kept in Redis and
 * nowhere else.
 *
 * <p>A hold is a courtesy, not the truth. PostgreSQL alone says how many seats a ride has
 * left; a hold only asks other passengers to leave some of them alone for a while. So
 * when Redis cannot be reached, holds stop being placed and stop being honoured, and
 * booking carries on exactly as it would with no holds at all.
 *
 * <p>Each ride has one sorted set, {@code holds:<ride>}, of members
 * {@code passenger:from:to:seats} scored by when they lapse. Each passenger has one key,
 * {@code hold:of:<passenger>}, naming their hold: a passenger holds seats on one ride at
 * a time.
 */
@Component
public class SeatHolds {

    /** @param toSeq the stop the passenger gets off at; the legs held are fromSeq up to it */
    public record Hold(long rideId, int fromSeq, int toSeq, int seats, Instant expiresAt) {
    }

    /** Redis could not be reached, so nothing was held. */
    public static class Unavailable extends RuntimeException {
        Unavailable(Throwable cause) {
            super("Seat holds are unavailable", cause);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SeatHolds.class);

    /*
     * KEYS[1] the ride's set. ARGV: now, expiry (both ms), passenger, from, to, seats, then
     * the seats PostgreSQL has free on each leg from..to-1. Returns 1 if the hold was
     * placed, 0 if the legs do not have the seats once other passengers' holds are left
     * alone. Lapsed holds are swept first, and the passenger's own earlier hold on this
     * ride is replaced, not added to.
     */
    private static final DefaultRedisScript<Long> PLACE = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local from, to, seats = tonumber(ARGV[4]), tonumber(ARGV[5]), tonumber(ARGV[6])
            local mine = ARGV[3] .. ':'
            local members = redis.call('ZRANGE', KEYS[1], 0, -1)
            local held = {}
            for _, m in ipairs(members) do
              if string.sub(m, 1, #mine) ~= mine then
                local f, t, s = string.match(m, '^%d+:(%d+):(%d+):(%d+)$')
                for leg = tonumber(f), tonumber(t) - 1 do
                  held[leg] = (held[leg] or 0) + tonumber(s)
                end
              end
            end
            for leg = from, to - 1 do
              if tonumber(ARGV[7 + leg - from]) - (held[leg] or 0) < seats then
                return 0
              end
            end
            for _, m in ipairs(members) do
              if string.sub(m, 1, #mine) == mine then
                redis.call('ZREM', KEYS[1], m)
              end
            end
            redis.call('ZADD', KEYS[1], ARGV[2], mine .. from .. ':' .. to .. ':' .. seats)
            if redis.call('PTTL', KEYS[1]) < ARGV[2] - ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2] - ARGV[1])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public SeatHolds(StringRedisTemplate redis, Clock clock,
            @Value("${vayora.hold.ttl}") Duration ttl) {
        this.redis = redis;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Holds the seats if the legs have them once other passengers' holds are left alone.
     * Replaces any hold the passenger already had, on this ride or another.
     *
     * @param freePerLeg what PostgreSQL has free on each leg, fromSeq first
     * @return the hold, or empty if the seats are not there to hold
     * @throws Unavailable if Redis cannot be reached
     */
    public Optional<Hold> place(long passengerId, long rideId, int fromSeq, int toSeq, int seats,
            List<Integer> freePerLeg) {
        Instant now = clock.instant();
        // To the millisecond, which is what Redis keeps: the hold handed back is the hold
        // read back later.
        Instant expires = Instant.ofEpochMilli(now.plus(ttl).toEpochMilli());
        List<String> args = new ArrayList<>(List.of(String.valueOf(now.toEpochMilli()),
                String.valueOf(expires.toEpochMilli()), String.valueOf(passengerId),
                String.valueOf(fromSeq), String.valueOf(toSeq), String.valueOf(seats)));
        freePerLeg.forEach(free -> args.add(String.valueOf(free)));
        try {
            Optional<Hold> previous = of(passengerId);
            Long placed = redis.execute(PLACE, List.of(rideKey(rideId)), args.toArray());
            if (placed == null || placed == 0) {
                return Optional.empty();
            }
            // Only once the new hold exists: a refusal leaves the old one in place.
            previous.filter(old -> old.rideId() != rideId).ifPresent(old -> forget(passengerId, old));
            redis.opsForValue().set(passengerKey(passengerId),
                    rideId + ":" + fromSeq + ":" + toSeq + ":" + seats + ":" + expires.toEpochMilli(), ttl);
            return Optional.of(new Hold(rideId, fromSeq, toSeq, seats, expires));
        } catch (RuntimeException e) {
            throw new Unavailable(e);
        }
    }

    /** The passenger's live hold, if they have one. Empty when Redis cannot be reached. */
    public Optional<Hold> of(long passengerId) {
        String value;
        try {
            value = redis.opsForValue().get(passengerKey(passengerId));
        } catch (RuntimeException e) {
            log.warn("Seat holds unavailable: {}", e.getMessage());
            return Optional.empty();
        }
        if (value == null) {
            return Optional.empty();
        }
        String[] part = value.split(":");
        Hold hold = new Hold(Long.parseLong(part[0]), Integer.parseInt(part[1]),
                Integer.parseInt(part[2]), Integer.parseInt(part[3]),
                Instant.ofEpochMilli(Long.parseLong(part[4])));
        return hold.expiresAt().isAfter(clock.instant()) ? Optional.of(hold) : Optional.empty();
    }

    /** Gives the passenger's hold up, if they have one. Never fails its caller. */
    public void release(long passengerId) {
        try {
            String value = redis.opsForValue().get(passengerKey(passengerId));
            if (value != null) {
                String[] part = value.split(":");
                redis.opsForZSet().remove(rideKey(Long.parseLong(part[0])),
                        passengerId + ":" + part[1] + ":" + part[2] + ":" + part[3]);
                redis.delete(passengerKey(passengerId));
            }
        } catch (RuntimeException e) {
            log.warn("Seat hold not released for passenger {}: {}", passengerId, e.getMessage());
        }
    }

    /**
     * Seats held on each leg of the ride by passengers other than this one, by leg number.
     * Legs nobody holds are absent. Empty when Redis cannot be reached: an outage must not
     * stop anyone booking.
     */
    public Map<Integer, Integer> heldByOthers(long rideId, long passengerId) {
        Set<String> members;
        try {
            members = redis.opsForZSet().rangeByScore(rideKey(rideId),
                    Math.nextUp((double) clock.instant().toEpochMilli()), Double.POSITIVE_INFINITY);
        } catch (RuntimeException e) {
            log.warn("Seat holds unavailable, so none are honoured: {}", e.getMessage());
            return Map.of();
        }
        Map<Integer, Integer> held = new HashMap<>();
        if (members == null) {
            return held;
        }
        for (String member : members) {
            String[] part = member.split(":");
            if (Long.parseLong(part[0]) == passengerId) {
                continue;
            }
            for (int leg = Integer.parseInt(part[1]); leg < Integer.parseInt(part[2]); leg++) {
                held.merge(leg, Integer.parseInt(part[3]), Integer::sum);
            }
        }
        return held;
    }

    private void forget(long passengerId, Hold old) {
        redis.opsForZSet().remove(rideKey(old.rideId()),
                passengerId + ":" + old.fromSeq() + ":" + old.toSeq() + ":" + old.seats());
    }

    private static String rideKey(long rideId) {
        return "holds:" + rideId;
    }

    private static String passengerKey(long passengerId) {
        return "hold:of:" + passengerId;
    }
}
