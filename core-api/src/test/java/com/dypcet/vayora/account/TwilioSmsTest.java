package com.dypcet.vayora.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dypcet.vayora.account.MessageSender.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Feature 64: texts through Twilio's Messages API when an account is configured, against a
 * stand-in for Twilio. Nothing here reaches Twilio.
 */
class TwilioSmsTest {

    private static final String SID = "ACtest0000000000000000000000000000";
    private static final String TOKEN = "test-token";
    private static final String FROM = "+15005550006";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();

    private TwilioSms configured() {
        // The constructor that keeps the builder's request factory, so the stand-in answers.
        return new TwilioSms(builder, "https://api.twilio.com", SID, TOKEN, FROM);
    }

    @Test
    void aTextIsPostedToTheAccountsMessagesWithItsCredentials() {
        String basic = "Basic " + Base64.getEncoder().encodeToString((SID + ":" + TOKEN).getBytes(StandardCharsets.UTF_8));
        twilio.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/" + SID + "/Messages.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "To", "+919800000001", "From", FROM, "Body", "Your VAYORA code is 123456")))
                .andRespond(withSuccess("{\"sid\":\"SM1\",\"status\":\"queued\"}", MediaType.APPLICATION_JSON));

        configured().send("+919800000001", "Your VAYORA code is 123456");

        twilio.verify();
    }

    @Test
    void aRefusalFromTwilioIsNotSwallowed() {
        twilio.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/" + SID + "/Messages.json"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> configured().send("+919800000001", "x")).isInstanceOf(RestClientException.class);
        twilio.verify();
    }

    @Test
    void anAccountIsConfiguredWhole_OrNotAtAll() {
        assertThat(new TwilioSms(builder, "https://api.twilio.com", "", "", "").configured())
                .isFalse();
        assertThatThrownBy(() -> new TwilioSms(builder, "https://api.twilio.com", SID, "", FROM))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSenderSendsTextsThroughTwilioWhenConfiguredAndOtherwiseToTheOutbox() {
        MockMessageSender outbox = new MockMessageSender(Clock.systemUTC());
        var noMail = new StaticListableBeanFactory().getBeanProvider(JavaMailSender.class);

        DeliveringMessageSender simulated = new DeliveringMessageSender(outbox, noMail, "",
                new TwilioSms(builder, "https://api.twilio.com", "", "", ""));
        simulated.send(Channel.PHONE, "+919800000002", "hello");
        assertThat(simulated.name(Channel.PHONE)).isEqualTo(MockMessageSender.NAME);
        assertThat(outbox.outbox()).anyMatch(s -> s.to().equals("+919800000002"));

        twilio.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/" + SID + "/Messages.json"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        DeliveringMessageSender real = new DeliveringMessageSender(outbox, noMail, "", configured());
        assertThat(real.name(Channel.PHONE)).isEqualTo(TwilioSms.NAME);
        assertThatThrownBy(() -> real.send(Channel.PHONE, "+919800000003", "hello"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).isEqualTo("The text message could not be sent. Try again later.");
                });
        assertThat(outbox.outbox()).noneMatch(s -> s.to().equals("+919800000003"));
        twilio.verify();
    }
}
