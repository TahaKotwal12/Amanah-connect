package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.AdminEndpoints;
import com.amanahconnect.support.AdminEndpoints.Endpoint;
import com.amanahconnect.support.ApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Authorisation for the whole /api/v1/admin surface. The endpoints are read from the running application's handler
 * mappings, so a new admin endpoint is covered the moment it exists: it must refuse anonymous callers, community
 * admins, half-enrolled super admins and non-access tokens, and let a proper super admin through to the controller.
 */
class AdminAuthorizationIT extends AbstractAdminIT {

    @Autowired RequestMappingHandlerMapping handlerMapping;

    private List<Endpoint> endpoints() {
        List<Endpoint> endpoints = AdminEndpoints.all(handlerMapping);
        assertThat(endpoints).as("the admin API was found").hasSizeGreaterThanOrEqualTo(30);
        return endpoints;
    }

    private ApiClient.Response call(Endpoint endpoint, String token) {
        Object body = endpoint.method().equals("GET") || endpoint.method().equals("DELETE") ? null : Map.of();
        return token == null
                ? api.call(endpoint.method(), endpoint.concretePath(), body)
                : api.call(endpoint.method(), endpoint.concretePath(), body, "Authorization", ApiClient.bearer(token));
    }

    @Test
    void theSurfaceCoversEveryAreaOfTheModule() {
        List<String> patterns = endpoints().stream().map(Endpoint::pattern).toList();
        for (String area : List.of("/communities", "/plans", "/subscriptions", "/leads", "/stats", "/import")) {
            assertThat(patterns).as(area).anyMatch(p -> p.contains(area));
        }
    }

    @Test
    void anonymousCallersGet401OnEveryAdminEndpoint() {
        List<String> failures = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            ApiClient.Response response = call(endpoint, null);
            if (response.status() != 401 || !"UNAUTHENTICATED".equals(response.code())) {
                failures.add(endpoint + " -> " + response.status() + " " + response.body());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void aCommunityAdminGets403OnEveryAdminEndpoint() {
        String token = tokenFor(users.communityAdmin(), false);
        List<String> failures = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            ApiClient.Response response = call(endpoint, token);
            if (response.status() != 403 || !"FORBIDDEN".equals(response.code())) {
                failures.add(endpoint + " -> " + response.status() + " " + response.body());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void aSuperAdminWhoHasNotFinishedTwoFactorSetupGets403OnEveryAdminEndpoint() {
        String token = tokenFor(superAdmin, true);
        List<String> failures = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            ApiClient.Response response = call(endpoint, token);
            if (response.status() != 403 || !"MFA_SETUP_REQUIRED".equals(response.code())) {
                failures.add(endpoint + " -> " + response.status() + " " + response.body());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void theTemporaryTwoFactorLoginTokenIsNotAnAccessToken() {
        String mfaToken = jwtService.issueMfaToken(userRepository.findById(superAdmin.id()).orElseThrow());
        List<String> failures = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            ApiClient.Response response = call(endpoint, mfaToken);
            if (response.status() != 401) {
                failures.add(endpoint + " -> " + response.status());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void aSuperAdminWithTwoFactorCompletedGetsPastSecurityOnEveryAdminEndpoint() {
        List<String> failures = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            ApiClient.Response response = call(endpoint, superToken);
            // Whatever the controller answers (200, 400, 404, 415 ...) it must not be an authentication or authorisation refusal.
            if (response.status() == 401 || response.status() == 403 || response.status() >= 500) {
                failures.add(endpoint + " -> " + response.status() + " " + response.body());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void aDisabledOrDemotedSuperAdminsTokenStopsWorkingImmediately() {
        var disabled = users.superAdmin();
        String token = tokenFor(disabled, false);
        assertThat(api.get(ADMIN + "/stats", "Authorization", ApiClient.bearer(token)).status()).isEqualTo(200);

        jdbc.update("update users set status = 'DISABLED' where id = ?", disabled.id());

        ApiClient.Response response = api.get(ADMIN + "/stats", "Authorization", ApiClient.bearer(token));
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");

        var demoted = users.superAdmin();
        String demotedToken = tokenFor(demoted, false);
        jdbc.update("update users set role = 'COMMUNITY_ADMIN' where id = ?", demoted.id());
        assertThat(api.get(ADMIN + "/stats", "Authorization", ApiClient.bearer(demotedToken)).status()).isEqualTo(401);
    }
}
