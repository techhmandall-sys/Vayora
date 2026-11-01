package com.dypcet.vayora.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Password guessing is slowed per email address or phone number, whoever is guessing. */
@AutoConfigureMockMvc
class SignInThrottleIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private IdentityService identity;

    @Autowired
    private StringRedisTemplate redis;

    private String email;

    @BeforeEach
    void setUp() {
        email = UUID.randomUUID() + "@example.test";
        identity.register(email, PASSWORD, "Asha Patil", UserRole.PASSENGER);
    }

    @Test
    void fiveWrongPasswordsLockTheAccountForAWhileEvenAgainstTheRightOne() throws Exception {
        for (int i = 0; i < 5; i++) {
            signIn("email", email, "wrong-" + i).andExpect(status().isUnauthorized());
        }

        // Written another way, it is still the same account.
        String retryAfter = signIn("email", "  " + email.toUpperCase() + " ", PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Too many wrong passwords. Try again in 15 min."))
                .andReturn().getResponse().getHeader("Retry-After");
        // Seconds until the window that began with the first wrong one ends.
        assertThat(Long.parseLong(retryAfter)).isBetween(840L, 900L);

        // Redis forgets on its own, and holds a digest rather than the address.
        Long left = redis.getExpire(SignInThrottle.key("email:" + email));
        assertThat(left).isBetween(1L, 900L);
        assertThat(redis.keys("signin:fail:*")).noneMatch(key -> key.contains(email));

        // Nobody else is affected.
        String other = UUID.randomUUID() + "@example.test";
        identity.register(other, PASSWORD, "Ravi Desai", UserRole.DRIVER);
        signIn("email", other, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theRightPasswordForgivesTheWrongOnesBeforeIt() throws Exception {
        for (int i = 0; i < 4; i++) {
            signIn("email", email, "wrong-" + i).andExpect(status().isUnauthorized());
        }
        signIn("email", email, PASSWORD).andExpect(status().isOk());

        for (int i = 0; i < 4; i++) {
            signIn("email", email, "wrong-" + i).andExpect(status().isUnauthorized());
        }
        signIn("email", email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aPhoneNumberWrittenAnyWayIsOneCount() throws Exception {
        String digits = String.format("9%09d",
                Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));
        identity.registerByPhone(digits, PASSWORD, "Meera Kulkarni", UserRole.PASSENGER);
        String[] spellings = {digits, "0" + digits, "+91" + digits, "91" + digits, digits.substring(0, 5) + " "
                + digits.substring(5)};
        for (String spelling : spellings) {
            signIn("phone", spelling, "wrong-password").andExpect(status().isUnauthorized());
        }

        signIn("phone", "+91 " + digits, PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void anAddressNobodyHasIsCountedLikeAnyOther() throws Exception {
        // So the answer never tells whether an account exists.
        String nobody = UUID.randomUUID() + "@example.test";
        for (int i = 0; i < 5; i++) {
            signIn("email", nobody, "wrong-" + i).andExpect(status().isUnauthorized());
        }
        signIn("email", nobody, PASSWORD).andExpect(status().isTooManyRequests());
    }

    private ResultActions signIn(String field, String value, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"" + field + "\": \"" + value + "\", \"password\": \"" + password + "\"}"));
    }
}
