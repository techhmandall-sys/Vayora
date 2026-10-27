package com.dypcet.journex.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dypcet.journex.PostgresIntegrationTest;
import com.dypcet.journex.account.MessageSender.Channel;
import com.dypcet.journex.identity.IdentityService;
import com.dypcet.journex.identity.UserRole;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

/** With an SMTP server configured, email goes out for real; text messages still do not. */
@TestPropertySource(properties = {
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "journex.mail.from=noreply@journex.test"})
class EmailDeliveryIntegrationTest extends PostgresIntegrationTest {

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired
    private AccountService accounts;

    @Autowired
    private IdentityService identity;

    @Autowired
    private MockMessageSender mock;

    @Autowired
    private MessageSender sender;

    @Test
    void aConfirmationCodeArrivesByEmailAndNotInTheSimulatedOutbox() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        long member = identity.register(email, "correct-horse-battery", "Asha", UserRole.PASSENGER).id();

        accounts.requestCode(member, Channel.EMAIL);

        MimeMessage[] received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(received[0].getFrom()[0].toString()).isEqualTo("noreply@journex.test");
        assertThat(received[0].getSubject()).startsWith("VAYORA: ");
        assertThat(received[0].getContent().toString()).containsPattern("\\b[0-9]{6}\\b");
        assertThat(mock.outbox()).noneMatch(sent -> sent.to().equals(email));
        assertThat(sender.name(Channel.EMAIL)).isEqualTo(DeliveringMessageSender.SMTP);
    }

    @Test
    void textMessagesStillGoOnlyToTheOutbox() {
        assertThat(sender.name(Channel.PHONE)).isEqualTo(MockMessageSender.NAME);
        sender.send(Channel.PHONE, "+919800000001", "VAYORA: hello");
        assertThat(SMTP.getReceivedMessages()).isEmpty();
        assertThat(mock.outbox()).anyMatch(sent -> sent.to().equals("+919800000001"));
    }

    @Test
    void aCodeThatCannotBeSentLeavesNothingBehindSoTheMemberCanAskAgainAtOnce() {
        String email = UUID.randomUUID() + "@example.test";
        long member = identity.register(email, "correct-horse-battery", "Asha", UserRole.PASSENGER).id();
        SMTP.stop();
        assertThatThrownBy(() -> accounts.requestCode(member, Channel.EMAIL))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("could not be sent");

        SMTP.start();
        accounts.requestCode(member, Channel.EMAIL);
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    @Test
    void anEmailThatCannotBeSentIsReportedNotSwallowed() {
        SMTP.stop();
        assertThatThrownBy(() -> sender.send(Channel.EMAIL, "someone@example.test", "VAYORA: hello"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("could not be sent");
    }
}
