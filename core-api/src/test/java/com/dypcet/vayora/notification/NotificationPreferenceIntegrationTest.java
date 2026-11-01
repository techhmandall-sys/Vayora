package com.dypcet.vayora.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.account.MessageSender.Channel;
import com.dypcet.vayora.account.MockMessageSender;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.notification.NotificationService.Preferences;
import com.dypcet.vayora.notification.NotificationService.Type;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class NotificationPreferenceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private IdentityService identity;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private MockMessageSender mock;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MockMvc mvc;

    private long member;
    private String email;
    private String phone;

    @BeforeEach
    void setUp() {
        email = UUID.randomUUID() + "@example.test";
        member = identity.register(email, "correct-horse-battery", "Asha Patil", UserRole.PASSENGER).id();
        phone = "+91" + String.format("%010d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 10_000_000_000L));
        jdbc.update("UPDATE users SET phone = ? WHERE id = ?", phone, member);
    }

    @Test
    void nothingLeavesTheAppUntilAskedForAndOnlyToConfirmedAddresses() {
        notifications.send(member, Type.ACCOUNT_NOTICE, Text.of("first"), null);
        assertThat(copiesTo(email) + copiesTo(phone)).isZero();

        notifications.setPreferences(member, new Preferences(true, true));
        notifications.send(member, Type.ACCOUNT_NOTICE, Text.of("second"), null);
        assertThat(copiesTo(email) + copiesTo(phone)).isZero(); // neither address confirmed

        jdbc.update("UPDATE users SET email_verified_at = now(), phone_verified_at = now() WHERE id = ?", member);
        notifications.send(member, Type.ACCOUNT_NOTICE, Text.of("third"), null);
        assertThat(mock.outbox()).filteredOn(m -> m.to().equals(email)).singleElement()
                .satisfies(m -> {
                    assertThat(m.channel()).isEqualTo(Channel.EMAIL);
                    assertThat(m.text()).isEqualTo("VAYORA: third");
                });
        assertThat(copiesTo(phone)).isEqualTo(1);
        // In-app is always kept.
        assertThat(notifications.forUser(member)).hasSize(3);

        notifications.setPreferences(member, new Preferences(false, true));
        notifications.send(member, Type.ACCOUNT_NOTICE, Text.of("fourth"), null);
        assertThat(copiesTo(email)).isEqualTo(1);
        assertThat(copiesTo(phone)).isEqualTo(2);
    }

    @Test
    void aRolledBackChangeSendsNoCopy() {
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", member);
        notifications.setPreferences(member, new Preferences(true, false));

        transactions.executeWithoutResult(status -> {
            notifications.send(member, Type.ACCOUNT_NOTICE, Text.of("never happened"), null);
            status.setRollbackOnly();
        });

        assertThat(copiesTo(email)).isZero();
    }

    @Test
    void theEndpointsReadAndReplaceTheMembersOwnPreferences() throws Exception {
        String token = "Bearer " + tokens.issue(member, UserRole.PASSENGER);
        mvc.perform(get("/api/v1/notifications/preferences").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(false)).andExpect(jsonPath("$.sms").value(false));
        mvc.perform(put("/api/v1/notifications/preferences").header("Authorization", token)
                        .contentType("application/json").content("{\"email\": true, \"sms\": false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(true));
        mvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized());
    }

    private long copiesTo(String address) {
        return mock.outbox().stream().filter(m -> m.to().equals(address)).count();
    }
}
