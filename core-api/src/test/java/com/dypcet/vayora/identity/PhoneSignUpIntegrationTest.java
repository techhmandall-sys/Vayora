package com.dypcet.vayora.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.account.AccountService;
import com.dypcet.vayora.account.AccountService.ProfileUpdate;
import com.dypcet.vayora.account.MessageSender.Channel;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@AutoConfigureMockMvc
class PhoneSignUpIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IdentityService identity;

    @Autowired
    private AccountService accounts;

    /** Ten digits of this test's own, so no other test holds the number. */
    private String digits;

    @BeforeEach
    void setUp() {
        digits = String.format("9%09d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));
    }

    @Test
    void aMemberSignsUpWithAPhoneAndSignsInWithItWrittenAnotherWay() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                        .content("{\"phone\": \"" + digits.substring(0, 5) + " " + digits.substring(5) + "\","
                                + " \"password\": \"correct-horse-battery\", \"fullName\": \"Meera Kulkarni\","
                                + " \"role\": \"PASSENGER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").doesNotExist());

        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"phone\": \"+91" + digits + "\", \"password\": \"correct-horse-battery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.fullName").value("Meera Kulkarni"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"phone\": \"0" + digits + "\", \"password\": \"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        long id = identity.authenticateByPhone(digits, "correct-horse-battery").id();
        // Stored one way, unconfirmed until a code proves it.
        assertThat(accounts.profile(id).phone()).isEqualTo("+91" + digits);
        assertThat(accounts.profile(id).phoneVerified()).isFalse();
    }

    @Test
    void aNumberBelongsToOneAccount() throws Exception {
        identity.registerByPhone(digits, "correct-horse-battery", "First", UserRole.PASSENGER);
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                        .content("{\"phone\": \"91" + digits + "\", \"password\": \"correct-horse-battery\","
                                + " \"fullName\": \"Second\", \"role\": \"DRIVER\"}"))
                .andExpect(status().isConflict());

        // Nor can a member who signed up by email take it in their profile.
        long other = identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery", "Other",
                UserRole.PASSENGER).id();
        assertThatThrownBy(() -> accounts.update(other, new ProfileUpdate(digits, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("belongs to another account");
    }

    @Test
    void aMemberWhoSignsInByPhoneCannotRemoveIt() {
        long id = identity.registerByPhone(digits, "correct-horse-battery", "Asha", UserRole.PASSENGER).id();
        assertThatThrownBy(() -> accounts.update(id, new ProfileUpdate("", null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("cannot be removed");
    }

    @Test
    void aMemberWithNoEmailIsToldSoWhenAskingForAnEmailCode() {
        long id = identity.registerByPhone(digits, "correct-horse-battery", "Asha", UserRole.PASSENGER).id();
        assertThatThrownBy(() -> accounts.requestCode(id, Channel.EMAIL))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no email address");
    }

    @Test
    void anEmailOrAPhoneButNotBothOrNeither() throws Exception {
        String rest = "\"password\": \"correct-horse-battery\", \"fullName\": \"X\", \"role\": \"PASSENGER\"}";
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{" + rest))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                        .content("{\"email\": \"" + UUID.randomUUID() + "@example.test\", \"phone\": \"" + digits + "\", " + rest))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                        .content("{\"phone\": \"12345\", " + rest))
                .andExpect(status().isBadRequest());
    }
}
