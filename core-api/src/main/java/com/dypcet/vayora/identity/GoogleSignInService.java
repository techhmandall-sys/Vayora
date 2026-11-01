package com.dypcet.vayora.identity;

import com.dypcet.vayora.identity.IdentityService.User;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Sign in with Google": the browser gets an ID token from Google, and this checks it the
 * way Google documents (signature against Google's published keys, issuer, audience equal
 * to our client ID, expiry) before trusting the email in it. Off unless a client ID is
 * configured.
 *
 * <p>An account is found by its Google identity, or else by the same email address if
 * Google says it has confirmed it, which then links the two. A new account needs the
 * person to say whether they are a passenger or a driver, as on the sign-up form.
 */
@Service
public class GoogleSignInService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final String clientId;
    private final NimbusJwtDecoder decoder;

    public GoogleSignInService(JdbcTemplate jdbc, PasswordEncoder passwords, Clock clock,
            @Value("${vayora.google.client-id:}") String clientId,
            @Value("${vayora.google.jwk-set-uri}") String jwkSetUri,
            @Value("${vayora.google.issuers}") List<String> issuers) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
        this.clientId = clientId.strip();
        if (this.clientId.isEmpty()) {
            this.decoder = null;
            return;
        }
        this.decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                jwt -> issuers.contains(jwt.getClaimAsString("iss")) ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not issued by Google", null)),
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(this.clientId)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not for this app", null))));
    }

    /** The client ID the browser needs, or null when Google sign-in is off. */
    public String clientId() {
        return decoder == null ? null : clientId;
    }

    /** @param roleIfNew passenger or driver, needed only when no account exists yet */
    @Transactional
    public User signIn(String idToken, UserRole roleIfNew) {
        if (decoder == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Google sign-in is not set up on this server");
        }
        Jwt token;
        try {
            token = decoder.decode(idToken);
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That Google sign-in could not be checked");
        }
        String subject = token.getSubject();
        String email = token.getClaimAsString("email");
        Object verified = token.getClaim("email_verified");
        if (subject == null || email == null || !(Boolean.TRUE.equals(verified) || "true".equals(verified))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google has not confirmed this email address");
        }
        email = email.strip().toLowerCase(Locale.ROOT);

        List<Account> found = jdbc.query(ACCOUNT + " WHERE google_sub = ?", MAPPER, subject);
        if (found.isEmpty()) {
            found = jdbc.query(ACCOUNT + " WHERE email = ?", MAPPER, email);
            if (!found.isEmpty()) {
                // Google has confirmed this address belongs to the person signing in.
                jdbc.update("UPDATE users SET google_sub = ?, email_verified_at = COALESCE(email_verified_at, ?)"
                        + " WHERE id = ?", subject, now(), found.get(0).user().id());
            }
        }
        if (!found.isEmpty()) {
            Account account = found.get(0);
            if ("SUSPENDED".equals(account.status())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, IdentityService.SUSPENDED);
            }
            return account.user();
        }

        if (roleIfNew == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No account uses this Google address yet. Choose passenger or driver to create one.");
        }
        if (roleIfNew == UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Administrators cannot self-register");
        }
        String name = token.getClaimAsString("name");
        String fullName = name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : name.strip();
        // No password: this account signs in through Google. A random one nobody knows keeps
        // the column honest and the password sign-in closed.
        byte[] unusable = new byte[32];
        RANDOM.nextBytes(unusable);
        Long id = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, google_sub, email_verified_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, email, passwords.encode(Base64.getEncoder().encodeToString(unusable)),
                fullName.length() > 100 ? fullName.substring(0, 100) : fullName, roleIfNew.name(), subject, now());
        return new User(id, email, fullName, roleIfNew);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record Account(User user, String status) {
    }

    private static final String ACCOUNT = "SELECT id, email, full_name, role, status FROM users";

    private static final org.springframework.jdbc.core.RowMapper<Account> MAPPER = (rs, n) -> new Account(
            new User(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"),
                    UserRole.valueOf(rs.getString("role"))),
            rs.getString("status"));
}
