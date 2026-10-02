package com.amanahconnect.config;

import com.amanahconnect.common.web.RequestPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/** The endpoints reachable without a bearer token. Everything else is authenticated by default. */
public final class PublicEndpoints {

    private static final Set<String> AUTH_POST =
            Set.of(
                    "/api/v1/auth/login",
                    "/api/v1/auth/login/2fa",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/password/forgot",
                    "/api/v1/auth/password/reset",
                    "/api/v1/auth/accept-invite");
    private static final Set<String> HEALTH =
            Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

    private PublicEndpoints() {}

    /** Whether the request targets a public endpoint, so a stale Authorization header must be ignored. */
    public static boolean matches(HttpServletRequest request) {
        String path = RequestPaths.of(request);
        String method = request.getMethod();
        if (path.startsWith("/api/v1/public/")) {
            return true;
        }
        if ("GET".equals(method) && (path.equals("/api/v1/ping") || HEALTH.contains(path))) {
            return true;
        }
        return "POST".equals(method) && AUTH_POST.contains(path);
    }
}
