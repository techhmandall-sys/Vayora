package com.dypcet.vayora.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebPushTest {

    // RFC 8291 section 5: the example push message and the keys that made it.
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String RECEIVER_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String SENDER_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String SENDER_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
            + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
            + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void encryptsTheRfcExampleToTheRfcsBytes() {
        byte[] body = WebPush.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII),
                WebPush.decode(RECEIVER_PUBLIC), WebPush.decode(AUTH_SECRET),
                WebPush.keys(SENDER_PUBLIC, SENDER_PRIVATE), WebPush.decode(SALT));

        assertThat(WebPush.encode(body)).isEqualTo(BODY);
    }

    @Test
    void aFreshEncryptionUsesANewSaltAndKeyEachTime() {
        byte[] message = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] first = WebPush.encrypt(message, WebPush.decode(RECEIVER_PUBLIC), WebPush.decode(AUTH_SECRET));
        byte[] second = WebPush.encrypt(message, WebPush.decode(RECEIVER_PUBLIC), WebPush.decode(AUTH_SECRET));

        assertThat(first).hasSize(86 + message.length + 1 + 16).isNotEqualTo(second);
    }

    @Test
    void theAuthorizationIsASignedTokenForThePushServiceWithTheServersKey() throws Exception {
        KeyPair server = WebPush.keys(SENDER_PUBLIC, SENDER_PRIVATE);
        String header = WebPush.authorization(server, "https://fcm.googleapis.com", "mailto:team@example.test",
                Instant.ofEpochSecond(1_800_000_000L));

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + SENDER_PUBLIC);
        String token = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = token.split("\\.");
        assertThat(new String(WebPush.decode(parts[1]), StandardCharsets.UTF_8))
                .isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1800000000,\"sub\":\"mailto:team@example.test\"}");

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(server.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(WebPush.decode(parts[2])).hasSize(64);
        assertThat(verifier.verify(WebPush.decode(parts[2]))).isTrue();
    }
}
