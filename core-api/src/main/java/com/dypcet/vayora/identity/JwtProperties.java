package com.dypcet.vayora.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secret shared HS256 secret; the gateway verifies tokens with the same value
 * @param ttl    lifetime of an access token
 */
@ConfigurationProperties("vayora.jwt")
public record JwtProperties(String secret, Duration ttl) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT_SECRET must be set and at least " + MIN_SECRET_BYTES + " bytes long");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("vayora.jwt.ttl must be a positive duration");
        }
    }
}
