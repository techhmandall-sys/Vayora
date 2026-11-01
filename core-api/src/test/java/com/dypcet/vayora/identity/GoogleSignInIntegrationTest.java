package com.dypcet.vayora.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Against a stand-in for Google: its own keys, published at a local address. */
@AutoConfigureMockMvc
class GoogleSignInIntegrationTest extends PostgresIntegrationTest {

    private static final String CLIENT = "test-client.apps.googleusercontent.com";
    private static final RSAKey GOOGLE_KEY = generate();
    private static final RSAKey STRANGER_KEY = generate();
    private static final HttpServer KEYS = publish();

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) {
        registry.add("vayora.google.client-id", () -> CLIENT);
        registry.add("vayora.google.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/certs");
    }

    @AfterAll
    static void stop() {
        KEYS.stop(0);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IdentityService identity;

    @Test
    void aNewPersonChoosesARoleOnceAndThenSignsInWithGoogleAlone() throws Exception {
        String email = UUID.randomUUID() + "@gmail.test";
        String sub = UUID.randomUUID().toString();
        String token = token(GOOGLE_KEY, sub, email, true, CLIENT, "https://accounts.google.com", 600);

        mvc.perform(get("/api/v1/auth/google")).andExpect(jsonPath("$.clientId").value(CLIENT));
        signIn(token, null).andExpect(status().isConflict());
        signIn(token, "PASSENGER").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("PASSENGER"))
                .andExpect(jsonPath("$.accessToken").isString());
        // Again, with no role: the same account.
        signIn(token(GOOGLE_KEY, sub, email, true, CLIENT, "accounts.google.com", 600), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void anExistingAccountWithTheSameConfirmedAddressIsLinked() throws Exception {
        String email = UUID.randomUUID() + "@gmail.test";
        long id = identity.register(email, "correct-horse-battery", "Asha Patil", UserRole.DRIVER).id();

        signIn(token(GOOGLE_KEY, UUID.randomUUID().toString(), email, true, CLIENT, "https://accounts.google.com", 600), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(id))
                .andExpect(jsonPath("$.user.role").value("DRIVER"));
    }

    @Test
    void aTokenThatIsNotGooglesOrNotForUsOrNotConfirmedIsRefused() throws Exception {
        String email = UUID.randomUUID() + "@gmail.test";
        String sub = UUID.randomUUID().toString();
        String issuer = "https://accounts.google.com";

        signIn(token(STRANGER_KEY, sub, email, true, CLIENT, issuer, 600), "PASSENGER").andExpect(status().isUnauthorized());
        signIn(token(GOOGLE_KEY, sub, email, true, "another-app", issuer, 600), "PASSENGER").andExpect(status().isUnauthorized());
        signIn(token(GOOGLE_KEY, sub, email, true, CLIENT, "https://evil.example", 600), "PASSENGER").andExpect(status().isUnauthorized());
        signIn(token(GOOGLE_KEY, sub, email, true, CLIENT, issuer, -600), "PASSENGER").andExpect(status().isUnauthorized());
        signIn(token(GOOGLE_KEY, sub, email, false, CLIENT, issuer, 600), "PASSENGER").andExpect(status().isUnauthorized());
        signIn("not a token", "PASSENGER").andExpect(status().isUnauthorized());
        signIn(token(GOOGLE_KEY, sub, email, true, CLIENT, issuer, 600), "ADMIN").andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String token, String role) throws Exception {
        String body = "{\"idToken\": \"" + token + "\"" + (role == null ? "" : ", \"role\": \"" + role + "\"") + "}";
        return mvc.perform(post("/api/v1/auth/google").contentType("application/json").content(body));
    }

    private static String token(RSAKey key, String sub, String email, boolean verified, String audience, String issuer,
            long secondsToLive) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(sub).audience(audience)
                .issueTime(Date.from(now.minusSeconds(Math.max(0, -secondsToLive) + 60)))
                .expirationTime(Date.from(now.plusSeconds(secondsToLive)))
                .claim("email", email).claim("email_verified", verified).claim("name", "Meera Kulkarni")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(GOOGLE_KEY.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("google-test-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer publish() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] keys = new JWKSet(GOOGLE_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                exchange.getResponseBody().write(keys);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
