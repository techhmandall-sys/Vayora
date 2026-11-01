package com.dypcet.vayora.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slows password guessing. After too many wrong passwords for one email address or phone
 * number, signing in with it is refused, the right password included, until the window
 * that began with the first wrong one ends. The counts live in Redis and expire on their
 * own; the key is a digest, so Redis keeps no addresses. An outage must not stop anyone
 * signing in, so without Redis nothing is counted.
 */
@Component
public class SignInThrottle {

    private static final Logger log = LoggerFactory.getLogger(SignInThrottle.class);

    /** The first wrong password starts the window; later ones only count. */
    private static final DefaultRedisScript<Long> FAIL = new DefaultRedisScript<>("""
            local failures = redis.call('INCR', KEYS[1])
            if failures == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return failures
            """, Long.class);

    /** Refused for now; the response says when to try again. */
    public static class Locked extends ResponseStatusException {

        private final long retryAfterSeconds;

        Locked(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong passwords. Try again in "
                    + Math.max(1, (retryAfterSeconds + 59) / 60) + " min.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
            return headers;
        }
    }

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration window;

    public SignInThrottle(StringRedisTemplate redis,
            @Value("${vayora.sign-in.max-failures}") int maxFailures,
            @Value("${vayora.sign-in.window}") Duration window) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /**
     * @param who {@code email:} or {@code phone:} and the normalised address
     * @throws Locked if it has had too many wrong passwords lately
     */
    public void refuseIfLocked(String who) {
        String failures;
        Long left;
        try {
            failures = redis.opsForValue().get(key(who));
            left = failures == null ? null : redis.getExpire(key(who));
        } catch (RuntimeException e) {
            log.warn("Sign-in throttle unavailable: {}", e.getMessage());
            return;
        }
        if (failures != null && Long.parseLong(failures) >= maxFailures) {
            throw new Locked(left == null || left <= 0 ? window.toSeconds() : left);
        }
    }

    /** Counts a wrong password. Never fails its caller. */
    public void failed(String who) {
        try {
            redis.execute(FAIL, List.of(key(who)), String.valueOf(window.toMillis()));
        } catch (RuntimeException e) {
            log.warn("Wrong password not counted: {}", e.getMessage());
        }
    }

    /** The right password forgives the wrong ones before it. Never fails its caller. */
    public void succeeded(String who) {
        try {
            redis.delete(key(who));
        } catch (RuntimeException e) {
            log.warn("Wrong passwords not forgotten: {}", e.getMessage());
        }
    }

    static String key(String who) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(who.getBytes(StandardCharsets.UTF_8));
            return "signin:fail:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime must provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
