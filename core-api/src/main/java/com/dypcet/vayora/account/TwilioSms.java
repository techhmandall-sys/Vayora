package com.dypcet.vayora.account;

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
 * Sends a text message through Twilio's Messages API (feature 64), when an account is
 * configured: TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM, all three or none.
 * Without them nothing is sent here and texts go to the simulated outbox, as before.
 *
 * <p>Twilio's test credentials, with TWILIO_FROM set to +15005550006, are answered as if
 * sent and send nothing: the way to try the integration without paying or texting anyone.
 * Texts to Indian numbers from a real account need the sender registered under TRAI's DLT
 * rules, which this project has not done.
 */
@Component
public class TwilioSms {

    public static final String NAME = "TWILIO";

    private final RestClient client;
    private final String accountSid;
    private final String from;

    @Autowired
    public TwilioSms(RestClient.Builder builder,
            @Value("${vayora.twilio.base-url}") String baseUrl,
            @Value("${vayora.twilio.account-sid}") String accountSid,
            @Value("${vayora.twilio.auth-token}") String authToken,
            @Value("${vayora.twilio.from}") String from,
            @Value("${vayora.twilio.timeout}") Duration timeout) {
        this(builder.requestFactory(timeouts(timeout)), baseUrl, accountSid, authToken, from);
    }

    /** Uses the builder's own request factory: a test binds a stand-in for Twilio to it. */
    TwilioSms(RestClient.Builder builder, String baseUrl, String accountSid, String authToken, String from) {
        this.client = configured(accountSid, authToken, from)
                ? builder.baseUrl(baseUrl).defaultHeaders(headers -> headers.setBasicAuth(accountSid, authToken)).build()
                : null;
        this.accountSid = accountSid;
        this.from = from;
    }

    private static boolean configured(String accountSid, String authToken, String from) {
        int set = (accountSid.isBlank() ? 0 : 1) + (authToken.isBlank() ? 0 : 1) + (from.isBlank() ? 0 : 1);
        if (set != 0 && set != 3) {
            throw new IllegalStateException(
                    "Set all of TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM, or none of them");
        }
        return set == 3;
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
     * Hands the text to Twilio. Throws if Twilio refuses it or cannot be reached; returns
     * once Twilio has accepted it, which is not the same as the phone having received it.
     */
    public void send(String to, String text) {
        if (client == null) {
            throw new IllegalStateException("No Twilio account is configured");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", to);
        form.add("From", from);
        form.add("Body", text);
        client.post().uri("/2010-04-01/Accounts/{sid}/Messages.json", accountSid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }
}
