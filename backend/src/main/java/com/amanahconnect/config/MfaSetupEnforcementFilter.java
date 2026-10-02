package com.amanahconnect.config;

import com.amanahconnect.auth.JwtService;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.ProblemResponseWriter;
import com.amanahconnect.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * While an account still has to enrol in 2FA, its access token carries {@code mfa_setup_required}
 * and may only call the enrolment endpoints (plus /auth/me and logout-all so the UI can show state
 * and sign out). Everything else answers 403 MFA_SETUP_REQUIRED.
 *
 * <p>Not a Spring bean on purpose: it is added to the security filter chain only, so it is not also
 * registered as a global servlet filter.
 */
final class MfaSetupEnforcementFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED =
            Set.of(
                    "/api/v1/auth/2fa/setup",
                    "/api/v1/auth/2fa/enable",
                    "/api/v1/auth/me",
                    "/api/v1/auth/logout-all");

    private final ProblemResponseWriter problems;

    MfaSetupEnforcementFilter(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && Boolean.TRUE.equals(token.getToken().getClaim(JwtService.CLAIM_MFA_SETUP_REQUIRED))
                && !ALLOWED.contains(RequestPaths.of(request))) {
            problems.write(
                    response,
                    ErrorCode.MFA_SETUP_REQUIRED,
                    "Set up two-factor authentication before using the application.");
            return;
        }
        chain.doFilter(request, response);
    }
}
