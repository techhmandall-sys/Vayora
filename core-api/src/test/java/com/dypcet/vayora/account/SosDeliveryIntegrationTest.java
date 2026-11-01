package com.dypcet.vayora.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.account.SosService.Raised;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;

/**
 * The emergency button with a real text-message provider (feature 64), Twilio replaced here:
 * a text that fails must never stop the alert, and the answer must say whom it missed.
 */
class SosDeliveryIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    private TwilioSms sms;

    @Autowired
    private IdentityService identity;

    @Autowired
    private AccountService accounts;

    @Autowired
    private SosService sos;

    @Test
    void aTextThatFailsNeverStopsTheAlertAndTheAnswerSaysWhomItMissed() {
        when(sms.configured()).thenReturn(true);
        doThrow(new RestClientException("Twilio is down")).when(sms).send(eq("+919811100002"), anyString());
        long member = identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Asha Patil", UserRole.PASSENGER).id();
        accounts.addContact(member, "Sunita Patil", "+919811100001", "mother");
        accounts.addContact(member, "Ravi Patil", "+919811100002", "brother");
        accounts.addContact(member, "Meera Kulkarni", "+919811100003", "friend");

        Raised raised = sos.raise(member, null, null, null);

        assertThat(raised.reallySent()).isTrue();
        assertThat(raised.contactsTold()).isEqualTo(2);
        assertThat(raised.contactsMissed()).isEqualTo(1);
        verify(sms, times(3)).send(anyString(), contains("Asha Patil pressed the emergency button"));
        assertThat(sos.open()).filteredOn(alert -> alert.id() == raised.alertId()).singleElement()
                .satisfies(alert -> {
                    assertThat(alert.sender()).isEqualTo(TwilioSms.NAME);
                    assertThat(alert.contactsTold()).isEqualTo(2);
                    assertThat(alert.contactsMissed()).isEqualTo(1);
                });
    }
}
