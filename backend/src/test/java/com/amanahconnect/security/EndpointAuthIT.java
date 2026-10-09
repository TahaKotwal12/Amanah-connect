package com.amanahconnect.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractDeskIT;
import com.amanahconnect.support.ApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Walks every controller mapping and checks its authentication rule. A new endpoint that is public by accident (or a public one nobody
 * listed here) fails the build: adding to {@link #PUBLIC} is a deliberate, reviewed act.
 */
class EndpointAuthIT extends AbstractDeskIT {

    /** The only endpoints reachable without a token, and why. */
    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/ping",                                   // liveness for the load balancer
            "GET /actuator/health", "GET /actuator/health/liveness", "GET /actuator/health/readiness",
            "GET /api/v1/public/invites/{token}",                 // self-registration form (token is the credential)
            "POST /api/v1/public/invites/{token}/register",
            "GET /api/v1/public/pay/{token}",                     // member pays a bill from the emailed link
            "GET /api/v1/public/exports/{token}",                 // emailed data-export link
            "POST /api/v1/public/leads",                          // landing-page contact form
            "POST /api/v1/webhooks/ses",                          // signed SNS notifications
            "POST /api/v1/auth/login", "POST /api/v1/auth/login/2fa", "POST /api/v1/auth/refresh", "POST /api/v1/auth/logout",
            "POST /api/v1/auth/password/forgot", "POST /api/v1/auth/password/reset", "POST /api/v1/auth/accept-invite");

    @Autowired RequestMappingHandlerMapping mappings;

    private record Route(String method, String pattern) {
        String key() {
            return method + " " + pattern;
        }

        String concrete() {
            return pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        }
    }

    private List<Route> routes() {
        List<Route> out = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (info.getPathPatternsCondition() == null) return;
            Set<String> methods = new TreeSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            for (var p : info.getPathPatternsCondition().getPatterns()) {
                if (p.getPatternString().equals("/error")) continue; // the container's error page; covered by anyRequest().authenticated()
                for (String m : methods) out.add(new Route(m, p.getPatternString()));
            }
        });
        return out;
    }

    private ApiClient.Response noToken(Route r) {
        Object body = List.of("POST", "PUT", "PATCH").contains(r.method) ? java.util.Map.of() : null;
        return api.call(r.method, r.concrete(), body);
    }

    @Test
    void theEnumerationSeesTheWholeApi() {
        List<Route> all = routes();
        assertThat(all.size()).isGreaterThan(150);
        assertThat(all.stream().filter(r -> r.pattern.startsWith("/api/v1/community/")).count()).isGreaterThan(80);
        assertThat(all.stream().filter(r -> r.pattern.startsWith("/api/v1/admin/")).count()).isGreaterThan(30);
        assertThat(all.stream().map(Route::key)).containsAll(PUBLIC);
    }

    @Test
    void onlyTheListedEndpointsAreReachableWithoutAToken() {
        List<String> openByAccident = new ArrayList<>();
        for (Route r : routes()) {
            if (PUBLIC.contains(r.key())) continue;
            ApiClient.Response response = noToken(r);
            if (response.status() != 401) openByAccident.add(r.key() + " -> " + response.status());
        }
        assertThat(openByAccident).as("every other endpoint must answer 401 without a token").isEmpty();
    }

    @Test
    void publicEndpointsAreTheOnesWeListedAndNothingMore() {
        Set<String> unlisted = new TreeSet<>();
        for (Route r : routes()) {
            if (PUBLIC.contains(r.key())) continue;
            if (!r.pattern.startsWith("/api/v1/") && !r.pattern.startsWith("/actuator/")) unlisted.add(r.key());
        }
        assertThat(unlisted).as("controllers mapped outside /api/v1 and /actuator").isEmpty();
        for (Route r : routes()) {
            if (r.pattern.startsWith("/api/v1/public/") || r.pattern.startsWith("/api/v1/webhooks/")) {
                assertThat(PUBLIC).as("public prefix used by an unlisted endpoint").contains(r.key());
            }
        }
    }

    @Test
    void adminEndpointsRefuseCommunityAdminsAndCommunityEndpointsRefusePlatformStaff() {
        List<String> wrong = new ArrayList<>();
        for (Route r : routes()) {
            Object body = List.of("POST", "PUT", "PATCH").contains(r.method) ? java.util.Map.of() : null;
            if (r.pattern.startsWith("/api/v1/admin/")) {
                ApiClient.Response response = call(sessionA, r.method, r.concrete(), body);
                if (response.status() != 403) wrong.add("community admin on " + r.key() + " -> " + response.status());
            } else if (r.pattern.startsWith("/api/v1/community/")) {
                ApiClient.Response response = asSuper(r.method, r.concrete(), body);
                if (response.status() != 403) wrong.add("super admin on " + r.key() + " -> " + response.status());
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void aGarbageOrExpiredTokenIsRefusedEverywhereThatIsNotPublic() {
        for (String bad : List.of("garbage", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.", "Bearer")) {
            assertThat(api.call("GET", "/api/v1/community/members", null, "Authorization", "Bearer " + bad).status()).isEqualTo(401);
            assertThat(api.call("GET", "/api/v1/admin/communities", null, "Authorization", "Bearer " + bad).status()).isEqualTo(401);
        }
    }

    @Test
    void methodSecurityIsOnAndUnknownPathsAreNotOpen() {
        assertThat(api.call("GET", "/api/v1/does-not-exist", null).status()).isEqualTo(401);
        assertThat(api.call("GET", "/actuator/env", null).status()).isIn(401, 404);
        assertThat(api.call("GET", "/actuator/prometheus", null).status()).isIn(401, 404);
        assertThat(api.call("GET", "/swagger-ui.html", null).status()).isIn(401, 404);
    }
}
