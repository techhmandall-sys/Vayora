package com.dypcet.vayora.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.account.AccountService.Chattiness;
import com.dypcet.vayora.account.AccountService.Gender;
import com.dypcet.vayora.account.AccountService.MyProfile;
import com.dypcet.vayora.account.AccountService.ProfileUpdate;
import com.dypcet.vayora.account.MessageSender.Channel;
import com.dypcet.vayora.account.MockMessageSender.Sent;
import com.dypcet.vayora.account.SosService.Raised;
import com.dypcet.vayora.audit.AuditService;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.notification.NotificationService;
import com.dypcet.vayora.rating.RatingService;
import com.dypcet.vayora.rating.RatingService.Profile;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@AutoConfigureMockMvc
class AccountIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Pattern CODE = Pattern.compile("code is (\\d{6})");

    @Autowired
    private IdentityService identity;

    @Autowired
    private AccountService accounts;

    @Autowired
    private SosService sos;

    @Autowired
    private MockMessageSender mock;

    @Autowired
    private RatingService ratings;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private AuditService audit;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    private long adminId;
    private long userId;
    private String email;
    private String phone;

    @BeforeEach
    void setUp() {
        adminId = identity.authenticate("admin@example.test", "admin-test-password").id();
        email = UUID.randomUUID() + "@example.test";
        userId = identity.register(email, PASSWORD, "Asha Patil", UserRole.PASSENGER).id();
        // Unique per test: the mock outbox is shared by every test in the JVM.
        phone = "+91" + String.format("%010d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                10_000_000_000L));
    }

    @Test
    void aProfileStartsEmptyAndIsReplacedWhole() {
        MyProfile fresh = accounts.profile(userId);
        assertThat(fresh.email()).isEqualTo(email);
        assertThat(fresh.emailVerified()).isFalse();
        assertThat(fresh.phone()).isNull();
        assertThat(fresh.bio()).isNull();
        assertThat(fresh.smoking()).isNull();

        MyProfile saved = accounts.update(userId, new ProfileUpdate(phone, "  Teacher from Kolhapur.  ",
                1998, Gender.FEMALE, false, true, null, Chattiness.QUIET));

        assertThat(saved.phone()).isEqualTo(phone);
        assertThat(saved.bio()).isEqualTo("Teacher from Kolhapur.");
        assertThat(saved.birthYear()).isEqualTo(1998);
        assertThat(saved.gender()).isEqualTo(Gender.FEMALE);
        assertThat(saved.smoking()).isFalse();
        assertThat(saved.pets()).isTrue();
        assertThat(saved.music()).isNull();
        assertThat(saved.chattiness()).isEqualTo(Chattiness.QUIET);

        MyProfile cleared = accounts.update(userId,
                new ProfileUpdate("", " ", null, null, null, null, null, null));
        assertThat(cleared.phone()).isNull();
        assertThat(cleared.bio()).isNull();
        assertThat(cleared.pets()).isNull();
    }

    @Test
    void thePublicProfileShowsPreferencesButNeverThePhoneOrBirthYear() throws Exception {
        accounts.update(userId, new ProfileUpdate(phone, "Teacher from Kolhapur.", 1998,
                Gender.FEMALE, false, null, true, Chattiness.TALKATIVE));

        Profile seen = ratings.profile(userId);

        assertThat(seen.bio()).isEqualTo("Teacher from Kolhapur.");
        assertThat(seen.age()).isEqualTo(Year.now().getValue() - 1998);
        assertThat(seen.smoking()).isFalse();
        assertThat(seen.pets()).isNull();
        assertThat(seen.music()).isTrue();
        assertThat(seen.chattiness()).isEqualTo("TALKATIVE");
        assertThat(seen.emailVerified()).isFalse();
        assertThat(seen.phoneVerified()).isFalse();

        long other = identity.register(UUID.randomUUID() + "@example.test", PASSWORD, "Ravi Jadhav",
                UserRole.DRIVER).id();
        String body = mvc.perform(get("/api/v1/users/" + userId + "/profile")
                        .header("Authorization", "Bearer " + tokens.issue(other, UserRole.DRIVER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(phone).doesNotContain("1998").doesNotContain(email)
                .doesNotContain("FEMALE");
    }

    @Test
    void anEmailIsVerifiedByTheCodeThatWasSentAndOnlyOnce() {
        accounts.requestCode(userId, Channel.EMAIL);
        String code = codeSentTo(email);

        // Only a hash is stored.
        String stored = jdbc.queryForObject(
                "SELECT code_hash FROM verification_codes WHERE user_id = ?", String.class, userId);
        assertThat(stored).doesNotContain(code).startsWith("$2");

        assertStatus(HttpStatus.BAD_REQUEST,
                () -> accounts.confirmCode(userId, Channel.EMAIL, wrong(code)));
        assertThat(accounts.profile(userId).emailVerified()).isFalse();

        accounts.confirmCode(userId, Channel.EMAIL, code);

        assertThat(accounts.profile(userId).emailVerified()).isTrue();
        assertThat(ratings.profile(userId).emailVerified()).isTrue();
        assertStatus(HttpStatus.CONFLICT, () -> accounts.confirmCode(userId, Channel.EMAIL, code));
        assertStatus(HttpStatus.CONFLICT, () -> accounts.requestCode(userId, Channel.EMAIL));
    }

    @Test
    void aSecondCodeCannotBeRequestedStraightAway() {
        accounts.requestCode(userId, Channel.EMAIL);

        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> accounts.requestCode(userId, Channel.EMAIL));

        // Once the wait is over a new code replaces the old one.
        String first = codeSentTo(email);
        jdbc.update("UPDATE verification_codes SET created_at = created_at - interval '2 minutes' "
                + "WHERE user_id = ?", userId);
        accounts.requestCode(userId, Channel.EMAIL);
        String second = codeSentTo(email);
        if (!first.equals(second)) {
            assertStatus(HttpStatus.BAD_REQUEST,
                    () -> accounts.confirmCode(userId, Channel.EMAIL, first));
        }
        accounts.confirmCode(userId, Channel.EMAIL, second);
        assertThat(accounts.profile(userId).emailVerified()).isTrue();
    }

    @Test
    void aDayAllowsOnlySoManyCodesWhateverTheWaitBetweenThem() {
        // Each one asked for once the wait after the last is over.
        for (int i = 0; i < 10; i++) {
            accounts.requestCode(userId, Channel.EMAIL);
            jdbc.update("UPDATE verification_codes SET created_at = created_at - interval '2 minutes' "
                    + "WHERE user_id = ?", userId);
        }

        assertStatus(HttpStatus.TOO_MANY_REQUESTS, () -> accounts.requestCode(userId, Channel.EMAIL));
        // The phone is counted apart.
        accounts.update(userId, new ProfileUpdate(phone, null, null, null, null, null, null, null));
        accounts.requestCode(userId, Channel.PHONE);

        // A day after the first, the member may ask again.
        jdbc.update("UPDATE verification_codes SET created_at = created_at - interval '1 day' "
                + "WHERE user_id = ? AND channel = 'EMAIL'", userId);
        accounts.requestCode(userId, Channel.EMAIL);
    }

    @Test
    void tooManyWrongGuessesSpendTheCodeAndAnExpiredCodeIsRefused() {
        accounts.requestCode(userId, Channel.EMAIL);
        String code = codeSentTo(email);
        for (int i = 0; i < 5; i++) {
            assertStatus(HttpStatus.BAD_REQUEST,
                    () -> accounts.confirmCode(userId, Channel.EMAIL, wrong(code)));
        }

        // Even the right code no longer works.
        assertStatus(HttpStatus.TOO_MANY_REQUESTS,
                () -> accounts.confirmCode(userId, Channel.EMAIL, code));
        assertThat(accounts.profile(userId).emailVerified()).isFalse();

        long other = identity.register(UUID.randomUUID() + "@example.test", PASSWORD, "Ravi Jadhav",
                UserRole.DRIVER).id();
        accounts.requestCode(other, Channel.EMAIL);
        jdbc.update("UPDATE verification_codes SET expires_at = now() - interval '1 second' "
                + "WHERE user_id = ?", other);
        assertStatus(HttpStatus.CONFLICT, () -> accounts.confirmCode(other, Channel.EMAIL, "000000"));
        assertStatus(HttpStatus.CONFLICT, () -> accounts.confirmCode(
                identity.register(UUID.randomUUID() + "@example.test", PASSWORD, "Nobody",
                        UserRole.PASSENGER).id(), Channel.EMAIL, "000000"));
    }

    @Test
    void aPhoneNeedsANumberAndChangingTheNumberUndoesTheProof() {
        assertStatus(HttpStatus.CONFLICT, () -> accounts.requestCode(userId, Channel.PHONE));

        accounts.update(userId, new ProfileUpdate(phone, null, null, null, null, null, null, null));
        accounts.requestCode(userId, Channel.PHONE);
        String code = codeSentTo(phone);

        // A code for one number proves nothing about another.
        String newPhone = phone.substring(0, phone.length() - 1) + (phone.endsWith("7") ? "8" : "7");
        accounts.update(userId, new ProfileUpdate(newPhone, null, null, null, null, null, null, null));
        assertStatus(HttpStatus.CONFLICT, () -> accounts.confirmCode(userId, Channel.PHONE, code));
        assertThat(accounts.profile(userId).phoneVerified()).isFalse();

        jdbc.update("UPDATE verification_codes SET created_at = created_at - interval '2 minutes' "
                + "WHERE user_id = ?", userId);
        accounts.requestCode(userId, Channel.PHONE);
        accounts.confirmCode(userId, Channel.PHONE, codeSentTo(newPhone));
        assertThat(accounts.profile(userId).phoneVerified()).isTrue();

        // Saving the profile with the same number keeps the proof; a different one loses it.
        accounts.update(userId, new ProfileUpdate(newPhone, "Hello", null, null, null, null, null, null));
        assertThat(accounts.profile(userId).phoneVerified()).isTrue();
        accounts.update(userId, new ProfileUpdate(phone, "Hello", null, null, null, null, null, null));
        assertThat(accounts.profile(userId).phoneVerified()).isFalse();
    }

    @Test
    void atMostThreeEmergencyContactsAndOnlyYourOwnCanBeRemoved() {
        long first = accounts.addContact(userId, "  Meera Patil ", "+919800000001", "Mother").id();
        accounts.addContact(userId, "Sunil Patil", "9800000002", " ");
        accounts.addContact(userId, "Neha", "+919800000003", "Friend");

        assertStatus(HttpStatus.CONFLICT,
                () -> accounts.addContact(userId, "Fourth", "+919800000004", null));
        assertThat(accounts.contacts(userId)).extracting(c -> c.name())
                .containsExactly("Meera Patil", "Sunil Patil", "Neha");
        assertThat(accounts.contacts(userId).get(1).relation()).isNull();

        long other = identity.register(UUID.randomUUID() + "@example.test", PASSWORD, "Ravi Jadhav",
                UserRole.DRIVER).id();
        assertStatus(HttpStatus.NOT_FOUND, () -> accounts.removeContact(other, first));
        accounts.removeContact(userId, first);
        assertThat(accounts.contacts(userId)).hasSize(2);
    }

    @Test
    void anEmergencyIsRecordedAndSaysPlainlyThatNobodyWasReallyMessaged() {
        String contactPhone = phone;
        accounts.addContact(userId, "Meera Patil", contactPhone, "Mother");

        Raised raised = sos.raise(userId, null, 16.70501, 74.24332);

        assertThat(raised.contactsTold()).isEqualTo(1);
        assertThat(raised.reallySent()).isFalse();
        assertThat(raised.emergencyNumber()).isEqualTo("112");
        assertThat(mock.outbox()).filteredOn(sent -> sent.to().equals(contactPhone))
                .singleElement().satisfies(sent -> {
                    assertThat(sent.channel()).isEqualTo(Channel.PHONE);
                    assertThat(sent.text()).contains("Asha Patil").contains("16.70501, 74.24332")
                            .contains("112");
                });
        assertThat(sos.open()).filteredOn(alert -> alert.id() == raised.alertId())
                .singleElement().satisfies(alert -> {
                    assertThat(alert.userName()).isEqualTo("Asha Patil");
                    assertThat(alert.lat()).isEqualTo(16.70501);
                    assertThat(alert.lon()).isEqualTo(74.24332);
                    assertThat(alert.sender()).isEqualTo("MOCK");
                    assertThat(alert.rideId()).isNull();
                });
        assertThat(notifications.forUser(adminId)).first().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(NotificationService.Type.SOS_RAISED);
            assertThat(notice.message()).startsWith("Asha Patil pressed the emergency button");
        });

        // Pressing again straight away repeats nothing.
        Raised again = sos.raise(userId, null, null, null);
        assertThat(again.alertId()).isEqualTo(raised.alertId());
        assertThat(mock.outbox()).filteredOn(sent -> sent.to().equals(contactPhone)).hasSize(1);
    }

    @Test
    void anEmergencyWithNoContactsNoPositionAndSomeoneElsesRideIsStillRecorded() {
        Raised raised = sos.raise(userId, Long.MAX_VALUE, null, null);

        assertThat(raised.contactsTold()).isZero();
        assertThat(sos.open()).filteredOn(alert -> alert.id() == raised.alertId())
                .singleElement().satisfies(alert -> {
                    assertThat(alert.lat()).isNull();
                    assertThat(alert.rideId()).isNull();
                });
    }

    @Test
    void acknowledgingAnAlertTakesItOffTheQueueOnceAndIsAudited() {
        long alertId = sos.raise(userId, null, null, null).alertId();

        sos.acknowledge(adminId, alertId);

        assertThat(sos.open()).noneMatch(alert -> alert.id() == alertId);
        assertThat(audit.latest().get(0).action()).isEqualTo("SOS_ACKNOWLEDGED");
        assertThat(audit.latest().get(0).targetId()).isEqualTo(alertId);
        assertStatus(HttpStatus.NOT_FOUND, () -> sos.acknowledge(adminId, alertId));
    }

    @Test
    void theEndpointsValidateAndKeepTheOutboxAndTheQueueForAdministrators() throws Exception {
        String admin = "Bearer " + tokens.issue(adminId, UserRole.ADMIN);
        String member = "Bearer " + tokens.issue(userId, UserRole.PASSENGER);

        mvc.perform(get("/api/v1/me/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/profile").header("Authorization", member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(false));
        mvc.perform(put("/api/v1/me/profile").header("Authorization", member)
                        .contentType("application/json").content("{\"phone\": \"12345\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/profile").header("Authorization", member)
                        .contentType("application/json").content("{\"birthYear\": 1800}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/profile").header("Authorization", member)
                        .contentType("application/json")
                        .content("{\"phone\": \"" + phone + "\", \"chattiness\": \"QUIET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.chattiness").value("QUIET"));

        mvc.perform(post("/api/v1/me/verification/fax/request").header("Authorization", member))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/verification/email/request").header("Authorization", member))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/me/verification/email/confirm").header("Authorization", member)
                        .contentType("application/json").content("{\"code\": \"12ab\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/verification/email/confirm").header("Authorization", member)
                        .contentType("application/json")
                        .content("{\"code\": \"" + codeSentTo(email) + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/me/emergency-contacts").header("Authorization", member)
                        .contentType("application/json").content("{\"name\": \"Meera\", \"phone\": \"abc\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/emergency-contacts").header("Authorization", member)
                        .contentType("application/json")
                        .content("{\"name\": \"Meera\", \"phone\": \"+919800000001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());

        mvc.perform(post("/api/v1/sos").header("Authorization", member)
                        .contentType("application/json").content("{\"lat\": 120}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/sos").header("Authorization", admin)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sos").header("Authorization", member)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contactsTold").value(1))
                .andExpect(jsonPath("$.reallySent").value(false))
                .andExpect(jsonPath("$.emergencyNumber").value("112"));

        for (String path : List.of("/admin/sos", "/admin/mock-outbox")) {
            mvc.perform(get("/api/v1" + path).header("Authorization", member))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1" + path).header("Authorization", admin))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/admin/sos/1/acknowledge").header("Authorization", member))
                .andExpect(status().isForbidden());
    }

    /** The code in the newest mock message to that address. */
    private String codeSentTo(String destination) {
        for (Sent sent : mock.outbox()) {
            Matcher matcher = CODE.matcher(sent.text());
            if (sent.to().equals(destination) && matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new AssertionError("No code was sent to " + destination);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }
}
