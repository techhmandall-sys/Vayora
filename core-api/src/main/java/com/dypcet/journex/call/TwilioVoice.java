package com.dypcet.journex.call;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Masked calls through Twilio's Voice API (feature 61), when an account is configured: the
 * same three settings as texts. Twilio rings the caller from the platform's number; when
 * they answer, it dials the other side, again from the platform's number. Neither ever
 * sees the other's number.
 */
@Component
public class TwilioVoice {

    public static final String NAME = "twilio";

    private final RestClient client;
    private final String accountSid;
    private final String from;

    @Autowired
    public TwilioVoice(RestClient.Builder builder,
            @Value("${journex.twilio.base-url}") String baseUrl,
            @Value("${journex.twilio.account-sid}") String accountSid,
            @Value("${journex.twilio.auth-token}") String authToken,
            @Value("${journex.twilio.from}") String from,
            @Value("${journex.twilio.timeout}") Duration timeout) {
        this(builder.requestFactory(timeouts(timeout)), baseUrl, accountSid, authToken, from);
    }

    /** Uses the builder's own request factory: a test binds a stand-in for Twilio to it. */
    TwilioVoice(RestClient.Builder builder, String baseUrl, String accountSid, String authToken, String from) {
        boolean configured = !accountSid.isBlank() && !authToken.isBlank() && !from.isBlank();
        this.client = configured
                ? builder.baseUrl(baseUrl).defaultHeaders(headers -> headers.setBasicAuth(accountSid, authToken)).build()
                : null;
        this.accountSid = accountSid;
        this.from = from;
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

    /**
     * Rings {@code caller}, then connects them to {@code callee}, both from the platform's
     * number. Returns Twilio's id for the call. Throws if Twilio refuses or cannot be reached.
     */
    public String bridge(String caller, String callee) {
        if (client == null) {
            throw new IllegalStateException("No Twilio account is configured");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", caller);
        form.add("From", from);
        form.add("Twiml", "<Response><Say>VAYORA is connecting your call.</Say>"
                + "<Dial callerId=\"" + xml(from) + "\"><Number>" + xml(callee) + "</Number></Dial></Response>");
        JsonNode call = client.post().uri("/2010-04-01/Accounts/{sid}/Calls.json", accountSid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(JsonNode.class);
        return call == null ? "" : call.path("sid").asText();
    }

    /** Phone numbers are digits and a plus, but nothing reaches the TwiML unescaped. */
    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
