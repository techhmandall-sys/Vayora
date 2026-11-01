package com.dypcet.vayora.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Razorpay's payments API (feature 46), when its keys are set: RAZORPAY_KEY_ID and
 * RAZORPAY_KEY_SECRET, both or neither. Amounts are in paise, as Razorpay counts them.
 */
@Component
public class RazorpayClient {

    /**
     * A payment as Razorpay reports it.
     *
     * @param status  created, authorized, captured, refunded or failed
     * @param method  card, upi, wallet, netbanking and so on
     */
    public record Payment(String id, long amount, String currency, String status, String orderId, String method) {
    }

    private final RestClient client;
    private final String keyId;
    private final String keySecret;

    @Autowired
    public RazorpayClient(RestClient.Builder builder,
            @Value("${vayora.payment.razorpay.base-url}") String baseUrl,
            @Value("${vayora.payment.razorpay.key-id}") String keyId,
            @Value("${vayora.payment.razorpay.key-secret}") String keySecret,
            @Value("${vayora.payment.razorpay.timeout}") Duration timeout) {
        this(builder.requestFactory(timeouts(timeout)), baseUrl, keyId, keySecret);
    }

    /** Uses the builder's own request factory: a test binds a stand-in for Razorpay to it. */
    RazorpayClient(RestClient.Builder builder, String baseUrl, String keyId, String keySecret) {
        if (keyId.isBlank() != keySecret.isBlank()) {
            throw new IllegalStateException("Set both RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET, or neither");
        }
        this.client = keyId.isBlank() ? null
                : builder.baseUrl(baseUrl).defaultHeaders(headers -> headers.setBasicAuth(keyId, keySecret)).build();
        this.keyId = keyId;
        this.keySecret = keySecret;
    }

    private static SimpleClientHttpRequestFactory timeouts(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return factory;
    }

    public boolean configured() {
        return client != null;
    }

    /** The key's public half, which the browser's checkout needs. Never the secret. */
    public String keyId() {
        return keyId;
    }

    /** A test key: Razorpay's test mode, where no real money moves. */
    public boolean testMode() {
        return keyId.startsWith("rzp_test_");
    }

    /** Opens an order for this many paise and returns its id, which the checkout pays. */
    public String createOrder(long paise, String receipt) {
        JsonNode order = answered(api().post().uri("/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", paise, "currency", "INR", "receipt", receipt))
                .retrieve().body(JsonNode.class));
        return order.path("id").asText();
    }

    public Payment payment(String paymentId) {
        JsonNode p = answered(api().get().uri("/v1/payments/{id}", paymentId).retrieve().body(JsonNode.class));
        return new Payment(p.path("id").asText(), p.path("amount").asLong(), p.path("currency").asText(),
                p.path("status").asText(), p.path("order_id").asText(), p.path("method").asText());
    }

    /** An empty reply is no answer: callers treat it as they treat Razorpay not answering at all. */
    private static JsonNode answered(JsonNode body) {
        if (body == null) {
            throw new RestClientException("Razorpay sent an empty reply");
        }
        return body;
    }

    public void capture(String paymentId, long paise) {
        api().post().uri("/v1/payments/{id}/capture", paymentId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", paise, "currency", "INR")).retrieve().toBodilessEntity();
    }

    public void refund(String paymentId, long paise) {
        api().post().uri("/v1/payments/{id}/refund", paymentId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", paise)).retrieve().toBodilessEntity();
    }

    /**
     * Whether Razorpay made this signature for this order and payment: an HMAC-SHA256 of
     * "order|payment" with the key secret, compared in constant time.
     */
    public boolean signatureValid(String orderId, String paymentId, String signature) {
        if (client == null || orderId == null || paymentId == null || signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(
                    mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private RestClient api() {
        if (client == null) {
            throw new IllegalStateException("No Razorpay keys are configured");
        }
        return client;
    }
}
