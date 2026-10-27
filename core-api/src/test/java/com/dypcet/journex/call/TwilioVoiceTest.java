package com.dypcet.journex.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** A masked call through Twilio's Voice API, against a stand-in for Twilio: nothing reaches Twilio. */
class TwilioVoiceTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();

    @Test
    void ringsTheCallerFromThePlatformsNumberThenDialsTheOtherSideFromItToo() {
        twilio.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/ACtest/Calls.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(
                        "To", "+919800000001", "From", "+15005550006",
                        "Twiml", "<Response><Say>VAYORA is connecting your call.</Say>"
                                + "<Dial callerId=\"+15005550006\"><Number>+919800000002</Number></Dial></Response>")))
                .andRespond(withSuccess("{\"sid\": \"CA123\", \"status\": \"queued\"}", MediaType.APPLICATION_JSON));

        String sid = new TwilioVoice(builder, "https://api.twilio.com", "ACtest", "token", "+15005550006")
                .bridge("+919800000001", "+919800000002");

        assertThat(sid).isEqualTo("CA123");
        twilio.verify();
    }

    @Test
    void withoutAnAccountNothingIsConfigured() {
        assertThat(new TwilioVoice(builder, "https://api.twilio.com", "", "", "").configured()).isFalse();
    }
}
