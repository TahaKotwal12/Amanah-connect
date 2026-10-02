package com.amanahconnect.config;

import com.amanahconnect.auth.JwtService;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.auth.UserStatus;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.ProblemResponseWriter;
import com.amanahconnect.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * An access token lives for minutes, but a super admin account can be disabled or demoted sooner. For the
 * platform-admin API (the most powerful one, with low traffic) the account is re-checked on every request: a
 * SUPER_ADMIN token whose user is no longer an ACTIVE SUPER_ADMIN is answered 401, immediately.
 *
 * <p>Not a Spring bean on purpose: it is added to the security filter chain only.
 */
final class AdminAccountFilter extends OncePerRequestFilter {

    private final UserRepository users;
    private final ProblemResponseWriter problems;

    AdminAccountFilter(UserRepository users, ProblemResponseWriter problems) {
        this.users = users;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith("/api/v1/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && UserRole.SUPER_ADMIN.name().equals(token.getToken().getClaimAsString(JwtService.CLAIM_ROLE))) {
            boolean stillValid;
            try {
                stillValid = users.existsByIdAndRoleAndStatus(UUID.fromString(token.getToken().getSubject()), UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
            } catch (IllegalArgumentException e) {
                stillValid = false;
            }
            if (!stillValid) {
                SecurityContextHolder.clearContext();
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                problems.write(response, ErrorCode.UNAUTHENTICATED, "Authentication is required.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
