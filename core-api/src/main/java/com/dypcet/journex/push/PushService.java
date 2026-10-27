package com.dypcet.journex.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Browser push notifications, by the standard Web Push protocol. Off unless the server's
 * key pair is configured (scripts/vapid-keys.mjs makes one).
 *
 * <p>A browser gives the address its push service will accept messages at. Only addresses
 * at the configured push services are taken: anything else would let a member make this
 * server send requests wherever they chose.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    record Subscription(long id, String endpoint, byte[] p256dh, byte[] auth) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;
    private final KeyPair keys;
    private final String publicKey;
    private final String subject;
    private final List<String> allowedHosts;
    private final Duration timeToLive;

    public PushService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            @Value("${journex.push.public-key:}") String publicKey,
            @Value("${journex.push.private-key:}") String privateKey,
            @Value("${journex.push.subject}") String subject,
            @Value("${journex.push.allowed-hosts}") List<String> allowedHosts,
            @Value("${journex.push.time-to-live}") Duration timeToLive) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        boolean configured = !publicKey.isBlank() && !privateKey.isBlank();
        this.keys = configured ? WebPush.keys(publicKey, privateKey) : null;
        this.publicKey = configured ? publicKey.strip() : null;
        this.subject = subject;
        this.allowedHosts = allowedHosts;
        this.timeToLive = timeToLive;
    }

    /** The key a browser subscribes with, or null when push is not set up. */
    public String publicKey() {
        return publicKey;
    }

    @Transactional
    public void subscribe(long userId, String endpoint, String p256dh, String auth) {
        if (keys == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Push notifications are not set up on this server");
        }
        if (!allowed(endpoint)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is not a push service VAYORA sends to");
        }
        byte[] key;
        byte[] secret;
        try {
            key = WebPush.decode(p256dh);
            secret = WebPush.decode(auth);
            WebPush.publicKey(key);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The browser's keys are not valid");
        }
        if (secret.length != 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The browser's keys are not valid");
        }
        // One address is one browser: if another member signs in on it, it becomes theirs.
        jdbc.update("""
                INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?)
                ON CONFLICT (endpoint) DO UPDATE
                SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, created_at = now()""",
                userId, endpoint, key, secret);
    }

    @Transactional
    public void unsubscribe(long userId, String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint = ?", userId, endpoint);
    }

    /** True if the member has push on in at least one browser. */
    public boolean subscribed(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM push_subscriptions WHERE user_id = ?)", Boolean.class, userId));
    }

    /**
     * Sends to every browser the member has push on in. Never throws: a push that fails is
     * logged, and an address the push service says is gone is forgotten.
     */
    public void send(long userId, String body) {
        if (keys == null) {
            return;
        }
        List<Subscription> subscriptions;
        byte[] payload;
        try {
            subscriptions = jdbc.query("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?",
                    (rs, n) -> new Subscription(rs.getLong("id"), rs.getString("endpoint"), rs.getBytes("p256dh"),
                            rs.getBytes("auth")), userId);
            payload = mapper.writeValueAsBytes(Map.of("title", "VAYORA", "body", body, "url", "/notifications"));
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Push not sent to user {}: {}", userId, e.getMessage());
            return;
        }
        for (Subscription subscription : subscriptions) {
            try {
                int status = post(subscription, payload);
                if (status == 404 || status == 410) {
                    jdbc.update("DELETE FROM push_subscriptions WHERE id = ?", subscription.id());
                } else if (status >= 300) {
                    log.warn("Push service answered {} for subscription {}", status, subscription.id());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Push not delivered to subscription {}: {}", subscription.id(), e.getMessage());
            }
        }
    }

    private int post(Subscription subscription, byte[] payload) throws Exception {
        URI endpoint = URI.create(subscription.endpoint());
        String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("TTL", String.valueOf(timeToLive.toSeconds()))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("Urgency", "normal")
                .header("Authorization", WebPush.authorization(keys, audience, subject,
                        clock.instant().plus(Duration.ofHours(12))))
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        WebPush.encrypt(payload, subscription.p256dh(), subscription.auth())))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** HTTPS, at a configured push service or a subdomain of one. */
    boolean allowed(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
            return false;
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return allowedHosts.stream().anyMatch(allowed -> lower.equals(allowed) || lower.endsWith("." + allowed));
    }
}
