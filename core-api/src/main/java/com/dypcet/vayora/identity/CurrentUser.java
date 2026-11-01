package com.dypcet.vayora.identity;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the caller's identity from a verified access token. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
