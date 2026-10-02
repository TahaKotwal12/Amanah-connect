package com.amanahconnect.auth.web;

import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.ProblemResponseWriter;
import com.amanahconnect.common.web.RequestPaths;
import com.amanahconnect.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF protection for the two endpoints that act on the refresh <em>cookie</em> (refresh and
 * logout). Every other endpoint authenticates with a bearer header, which a browser never attaches
 * on its own, so those need no CSRF token.
 *
 * <p>A request must carry {@code X-Requested-With: amanah-web} (a custom header cannot be sent
 * cross-site without a CORS preflight) and an {@code Origin} that is the configured app origin. The
 * cookie is also SameSite=Strict, so this is a second, independent layer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CookieEndpointGuardFilter extends OncePerRequestFilter {

    public static final String REQUESTED_WITH_VALUE = "amanah-web";

    private static final Set<String> GUARDED = Set.of("/api/v1/auth/refresh", "/api/v1/auth/logout");

    private final AppProperties properties;
    private final ProblemResponseWriter problems;

    public CookieEndpointGuardFilter(AppProperties properties, ProblemResponseWriter problems) {
        this.properties = properties;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GUARDED.contains(RequestPaths.of(request)) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!REQUESTED_WITH_VALUE.equals(request.getHeader("X-Requested-With"))) {
            problems.write(response, ErrorCode.CSRF_HEADER_REQUIRED, "A required request header is missing.");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || !properties.cors().allowedOrigins().contains(origin)) {
            problems.write(response, ErrorCode.ORIGIN_NOT_ALLOWED, "This origin is not allowed.");
            return;
        }
        chain.doFilter(request, response);
    }
}
