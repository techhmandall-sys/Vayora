package com.dypcet.vayora.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a suspended member's requests even while their token is still valid. A token
 * cannot be recalled, so the account's standing is checked on every request instead.
 */
class SuspensionFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbc;

    SuspensionFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && isSuspended(token)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(
                    "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"" + IdentityService.SUSPENDED
                            + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isSuspended(JwtAuthenticationToken token) {
        List<String> status = jdbc.queryForList(
                "SELECT status FROM users WHERE id = ?", String.class,
                Long.parseLong(token.getToken().getSubject()));
        return status.contains("SUSPENDED");
    }
}
