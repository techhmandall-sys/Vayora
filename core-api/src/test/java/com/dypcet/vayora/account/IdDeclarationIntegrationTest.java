package com.dypcet.vayora.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dypcet.vayora.PostgresIntegrationTest;
import com.dypcet.vayora.account.AccountService.IdDocument;
import com.dypcet.vayora.identity.IdentityService;
import com.dypcet.vayora.identity.TokenService;
import com.dypcet.vayora.identity.UserRole;
import com.dypcet.vayora.rating.RatingService;
import com.dypcet.vayora.rating.RatingService.Profile;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * Feature 7, a labelled mock: a driver says which identity document they hold. Only the kind
 * is kept, nothing checks it, and it makes nobody verified.
 */
@AutoConfigureMockMvc
class IdDeclarationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private IdentityService identity;

    @Autowired
    private AccountService accounts;

    @Autowired
    private RatingService ratings;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Test
    void aDriverSaysWhichDocumentTheyHoldAndItVerifiesNothing() {
        long driver = user(UserRole.DRIVER);
        Profile before = ratings.profile(driver);

        assertThat(accounts.declareId(driver, IdDocument.DRIVING_LICENCE).declaredId())
                .isEqualTo(IdDocument.DRIVING_LICENCE);

        Profile after = ratings.profile(driver);
        assertThat(after.declaredId()).isEqualTo("DRIVING_LICENCE");
        assertThat(after.level()).isEqualTo(before.level());
        assertThat(after.emailVerified()).isFalse();
        assertThat(after.phoneVerified()).isFalse();

        assertThat(accounts.withdrawId(driver).declaredId()).isNull();
        assertThat(ratings.profile(driver).declaredId()).isNull();
    }

    @Test
    void onlyADriverMayDeclareOne() {
        long passenger = user(UserRole.PASSENGER);

        assertThatThrownBy(() -> accounts.declareId(passenger, IdDocument.PASSPORT))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).isEqualTo("Only a driver can declare an identity document");
                });
        assertThat(accounts.profile(passenger).declaredId()).isNull();
    }

    @Test
    void overHttpOnlyTheKindIsTakenAndANumberSentAlongIsKeptNowhere() throws Exception {
        long driver = user(UserRole.DRIVER);
        String auth = "Bearer " + tokens.issue(driver, UserRole.DRIVER);

        mvc.perform(put("/api/v1/me/id-declaration").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"document\": \"PASSPORT\", \"number\": \"Z1234567\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declaredId").value("PASSPORT"));
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", driver).toString())
                .contains("PASSPORT").doesNotContain("Z1234567");

        mvc.perform(put("/api/v1/me/id-declaration").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"document\": \"RATION_CARD\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/id-declaration").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/id-declaration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"document\": \"PAN\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/v1/me/id-declaration").header("Authorization", auth))
                .andExpect(status().isOk());
        assertThat(accounts.profile(driver).declaredId()).isNull();
    }

    private long user(UserRole role) {
        return identity.register(UUID.randomUUID() + "@example.test", "correct-horse-battery",
                "Test " + role, role).id();
    }
}
