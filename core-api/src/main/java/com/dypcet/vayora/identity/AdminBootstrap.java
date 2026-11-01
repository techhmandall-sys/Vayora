package com.dypcet.vayora.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the administrator named in the environment, if there is one and it does not
 * exist yet. Administrators cannot self-register, so this is the only way in. With no
 * ADMIN_EMAIL set, the application simply has no administrator.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final IdentityService identity;
    private final String email;
    private final String password;

    AdminBootstrap(IdentityService identity,
            @Value("${vayora.admin.email}") String email,
            @Value("${vayora.admin.password}") String password) {
        this.identity = identity;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank()) {
            return;
        }
        if (password.length() < 12) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least 12 characters");
        }
        if (identity.ensureAdmin(email, password)) {
            log.info("Created administrator {}", email);
        }
    }
}
