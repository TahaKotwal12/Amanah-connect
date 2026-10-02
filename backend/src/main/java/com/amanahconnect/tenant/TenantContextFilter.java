package com.amanahconnect.tenant;

import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.ProblemResponseWriter;
import com.amanahconnect.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * For /community/** requests by a COMMUNITY_ADMIN: resolves the community from the authenticated
 * principal (never from the request), binds it as the {@link TenantContext} for the request, and
 * answers 403 COMMUNITY_SUSPENDED to writes on a suspended or archived community (reads stay allowed so
 * admins can still see and export their data).
 *
 * <p>Requests by other roles pass through untouched; the authorisation rules reject them. Not a Spring
 * bean on purpose: it is added to the security filter chain only, after bearer authentication.
 */
public final class TenantContextFilter extends OncePerRequestFilter {

    static final String COMMUNITY_PREFIX = "/api/v1/community/";
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final TenantResolver resolver;
    private final ProblemResponseWriter problems;

    public TenantContextFilter(TenantResolver resolver, ProblemResponseWriter problems) {
        this.resolver = resolver;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith(COMMUNITY_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !isCommunityAdmin(token)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<CurrentTenant> tenant = resolver.resolve(UUID.fromString(token.getToken().getSubject()));
        if (tenant.isEmpty()) {
            problems.write(response, ErrorCode.FORBIDDEN, "No community is associated with this account.");
            return;
        }
        if (!tenant.get().writable() && !READ_METHODS.contains(request.getMethod())) {
            problems.write(
                    response,
                    ErrorCode.COMMUNITY_SUSPENDED,
                    "This community is " + tenant.get().status().toLowerCase() + " and is read-only.");
            return;
        }
        TenantContext.bind(tenant.get());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.unbind();
        }
    }

    private static boolean isCommunityAdmin(JwtAuthenticationToken token) {
        return "COMMUNITY_ADMIN".equals(token.getToken().getClaimAsString("role"));
    }
}
