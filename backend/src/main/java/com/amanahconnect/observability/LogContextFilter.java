package com.amanahconnect.observability;

import com.amanahconnect.auth.Tokens;
import com.amanahconnect.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds who and where to every log line of an authenticated request: {@code userHash} (a short SHA-256 of the user id, so lines of one user
 * can be followed without the id itself appearing in logs) and {@code communityId} (an opaque UUID). Added to the security chain after the
 * tenant is known; the request id is set earlier by RequestIdFilter.
 */
public final class LogContextFilter extends OncePerRequestFilter {

    public static final String USER_KEY = "userHash";
    public static final String COMMUNITY_KEY = "communityId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.getToken().getSubject() != null) {
            MDC.put(USER_KEY, hash(token.getToken().getSubject()));
        }
        TenantContext.current().ifPresent(t -> MDC.put(COMMUNITY_KEY, t.communityId().toString()));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(USER_KEY);
            MDC.remove(COMMUNITY_KEY);
        }
    }

    public static String hash(String userId) {
        return Tokens.sha256Hex("user:" + userId).substring(0, 12);
    }
}
