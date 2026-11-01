package com.dypcet.vayora.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PushIntegrationTest extends PostgresIntegrationTest {

    // RFC 8291's example receiver: its private key lets the test read what was sent.
    private static final String RECEIVER_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String RECEIVER_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PushService push;

    @Autowired
    private IdentityService identity;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void onlyAPushServiceAddressIsAccepted() throws Exception {
        long member = user();
        String auth = "Bearer " + tokens.issue(member, UserRole.PASSENGER);
        String body = "{\"endpoint\": \"%s\", \"keys\": {\"p256dh\": \"" + RECEIVER_PUBLIC + "\", \"auth\": \"" + AUTH + "\"}}";

        for (String refused : List.of("http://fcm.googleapis.com/fcm/send/x", "https://localhost/push",
                "https://169.254.169.254/latest", "https://fcm.googleapis.com.evil.example/x",
                "https://user@fcm.googleapis.com/x", "not a url")) {
            mvc.perform(post("/api/v1/me/push-subscriptions").header("Authorization", auth)
                            .contentType("application/json").content(body.formatted(refused)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/me/push").header("Authorization", auth))
                .andExpect(jsonPath("$.subscribed").value(false))
                .andExpect(jsonPath("$.publicKey").isString());

        String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        mvc.perform(post("/api/v1/me/push-subscriptions").header("Authorization", auth)
                        .contentType("application/json").content(body.formatted(endpoint)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/push").header("Authorization", auth)).andExpect(jsonPath("$.subscribed").value(true));
    }

    @Test
    void aPushArrivesEncryptedForTheBrowserAndAGoneAddressIsForgotten() throws Exception {
        long member = user();
        Map<String, byte[]> received = new ConcurrentHashMap<>();
        Map<String, String> authorization = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/live", exchange -> {
            received.put("body", exchange.getRequestBody().readAllBytes());
            authorization.put("value", exchange.getRequestHeaders().getFirst("Authorization"));
            authorization.put("encoding", exchange.getRequestHeaders().getFirst("Content-Encoding"));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/gone", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(410, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            // Written directly: the allowlist would refuse a local address, which is the point of it.
            for (String path : List.of("/live", "/gone")) {
                jdbc.update("INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, ?)",
                        member, base + path + "/" + UUID.randomUUID(), WebPush.decode(RECEIVER_PUBLIC), WebPush.decode(AUTH));
            }

            push.send(member, "The driver accepted your booking.");

            assertThat(authorization.get("encoding")).isEqualTo("aes128gcm");
            assertThat(authorization.get("value")).startsWith("vapid t=").contains(", k=" + push.publicKey());
            assertThat(new String(decrypt(received.get("body")), StandardCharsets.UTF_8))
                    .contains("\"body\":\"The driver accepted your booking.\"").contains("\"url\":\"/notifications\"");
            assertThat(jdbc.queryForList("SELECT endpoint FROM push_subscriptions WHERE user_id = ?", String.class, member))
                    .hasSize(1).allMatch(endpoint -> endpoint.contains("/live/"));
        } finally {
            server.stop(0);
        }
    }

    /** What the browser does with the message: RFC 8291 from the receiving end. */
    private static byte[] decrypt(byte[] body) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt();
        byte[] senderPublic = new byte[in.get()];
        in.get(senderPublic);
        byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(WebPush.privateKey(WebPush.decode(RECEIVER_PRIVATE)));
        ECPublicKey sender = WebPush.publicKey(senderPublic);
        agreement.doPhase(sender, true);
        byte[] prkKey = hmac(WebPush.decode(AUTH), agreement.generateSecret());
        byte[] ikm = hmac(prkKey, concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
                WebPush.decode(RECEIVER_PUBLIC), senderPublic, new byte[] {1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII),
                new byte[] {1})), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);
        assertThat(padded[padded.length - 1]).isEqualTo((byte) 2);
        return Arrays.copyOf(padded, padded.length - 1);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        ByteBuffer out = ByteBuffer.allocate(length);
        for (byte[] part : parts) {
            out.put(part);
        }
        return out.array();
    }

    private long user() {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery", "Asha", UserRole.PASSENGER).id();
    }
}
