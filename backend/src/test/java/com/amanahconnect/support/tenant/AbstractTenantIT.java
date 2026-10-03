package com.amanahconnect.support.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Base class for every integration test of a tenant-scoped module. Each test starts with two
 * communities, A and B, each with its own admin already signed in, and gets assertions for the rule that
 * matters most in a multi-tenant product:
 *
 * <p><b>admin A can never read, change or delete what belongs to community B, and finds out nothing
 * about it: the answer is 404, identical to the answer for an id that does not exist.</b>
 *
 * <p>Every endpoint under {@code /api/v1/community} must be exercised by at least one of the
 * {@code assertCrossTenant*} / {@code assertListHides} / {@code assertCreateCannotTargetOtherTenant}
 * helpers; {@code TenantCoverageIT} fails the build for any that is not.
 */
public abstract class AbstractTenantIT extends AbstractAuthIT {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired protected com.amanahconnect.support.TestData data;
    @Autowired private RequestMappingHandlerMapping handlerMapping;

    protected Community communityA;
    protected Community communityB;
    protected TestUser adminA;
    protected TestUser adminB;
    protected Session sessionA;
    protected Session sessionB;

    @BeforeEach
    void createTwoCommunities() {
        communityA = data.community();
        communityB = data.community();
        adminA = users.communityAdminOf(communityA);
        adminB = users.communityAdminOf(communityB);
        sessionA = loginOk(adminA);
        sessionB = loginOk(adminB);
    }

    // ---- calls ------------------------------------------------------------------------------

    protected ApiClient.Response asA(String method, String path, Object body) {
        return call(sessionA, method, path, body);
    }

    protected ApiClient.Response asB(String method, String path, Object body) {
        return call(sessionB, method, path, body);
    }

    protected ApiClient.Response call(Session session, String method, String path, Object body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Authorization";
        all[1] = session.bearer();
        System.arraycopy(headers, 0, all, 2, headers.length);
        return api.call(method, path, body, all);
    }

    // ---- assertions -------------------------------------------------------------------------

    /** A GETs B's resource: 404, same as a missing id. B reading its own resource succeeds (control). */
    protected void assertCrossTenantRead(String pathToResourceOfB) {
        assertHiddenFromA("GET", pathToResourceOfB, null);
        assertThat(asB("GET", pathToResourceOfB, null).status()).as("owner can read it (control)").isBetween(200, 299);
    }

    /**
     * A tries to modify B's resource: 404, same as a missing id, and nothing changed.
     *
     * @param assertUnchanged checks (e.g. through JDBC) that B's data is exactly as it was
     */
    protected void assertCrossTenantUpdate(String method, String pathToResourceOfB, Object body, Runnable assertUnchanged) {
        assertHiddenFromA(method, pathToResourceOfB, body);
        assertUnchanged.run();
        assertThat(asB(method, pathToResourceOfB, body).status()).as("owner can do it (control)").isBetween(200, 299);
    }

    protected void assertCrossTenantDelete(String pathToResourceOfB, Runnable assertUnchanged) {
        assertCrossTenantUpdate("DELETE", pathToResourceOfB, null, assertUnchanged);
    }

    /** A's list never shows B's item, and B's list does (control). */
    protected void assertListHides(String listPath, UUID idOfB) {
        ApiClient.Response forA = asA("GET", listPath, null);
        assertThat(forA.status()).isEqualTo(200);
        assertThat(forA.body()).as("A's list must not contain B's id").doesNotContain(idOfB.toString());
        assertThat(asB("GET", listPath, null).body()).as("B's list contains it (control)").contains(idOfB.toString());
        markCovered("GET", listPath);
    }

    /**
     * A creates something while also sending B's community id in the query, a header and the body.
     * The input must be ignored: the new row belongs to A (A can read it, B gets 404).
     */
    protected void assertCreateCannotTargetOtherTenant(
            String createPath, Map<String, Object> body, Function<ApiClient.Response, UUID> createdId, String readPathFormat) {
        Map<String, Object> forged = new java.util.HashMap<>(body);
        forged.put("communityId", communityB.getId().toString());
        forged.put("community_id", communityB.getId().toString());
        String path = createPath + (createPath.contains("?") ? "&" : "?") + "communityId=" + communityB.getId();

        ApiClient.Response created = call(sessionA, "POST", path, forged, "X-Community-Id", communityB.getId().toString());

        assertThat(created.status()).as(created.body()).isBetween(200, 299);
        UUID id = createdId.apply(created);
        assertThat(asA("GET", readPathFormat.formatted(id), null).status()).as("A owns what it created").isEqualTo(200);
        ApiClient.Response viaB = asB("GET", readPathFormat.formatted(id), null);
        assertThat(viaB.status()).as("B cannot see it").isEqualTo(404);
        markCovered("POST", createPath);
    }

    /**
     * For a resource that has no id because there is one per community (settings, counts, an export): A calls it, also
     * sending B's community id in the query and a header. A's call must succeed and leave B's view of the same resource
     * exactly as it was.
     *
     * @param readB reads B's own view of the resource (through B's session) as text
     */
    protected ApiClient.Response assertTenantSingleton(String method, String path, Object bodyForA, java.util.function.Supplier<String> readB) {
        String before = readB.get();
        String forged = path + (path.contains("?") ? "&" : "?") + "communityId=" + communityB.getId();
        ApiClient.Response response = call(sessionA, method, forged, bodyForA, "X-Community-Id", communityB.getId().toString());
        assertThat(response.status()).as(response.body()).isBetween(200, 299);
        assertThat(readB.get()).as("B's data must be untouched by A's call").isEqualTo(before);
        markCovered(method, path);
        return response;
    }

    private void assertHiddenFromA(String method, String pathToResourceOfB, Object body) {
        ApiClient.Response foreign = asA(method, pathToResourceOfB, body);
        ApiClient.Response missing = asA(method, withRandomId(pathToResourceOfB), body);

        assertThat(foreign.status()).as("cross-tenant must be 404, not 403: %s", foreign.body()).isEqualTo(404);
        assertThat(foreign.code()).isEqualTo("NOT_FOUND");
        assertThat(sameShape(foreign, missing))
                .as("a foreign resource must be indistinguishable from a missing one%nforeign: %s%nmissing: %s", foreign.body(), missing.body())
                .isTrue();
        markCovered(method, pathToResourceOfB);
    }

    private static boolean sameShape(ApiClient.Response a, ApiClient.Response b) {
        var x = a.json();
        var y = b.json();
        for (String field : new String[] {"type", "title", "status", "detail", "code"}) {
            if (!String.valueOf(x.get(field)).equals(String.valueOf(y.get(field)))) {
                return false;
            }
        }
        return a.status() == b.status();
    }

    private static String withRandomId(String path) {
        Matcher matcher = UUID_PATTERN.matcher(path);
        String last = null;
        while (matcher.find()) {
            last = matcher.group();
        }
        return last == null ? path : path.replace(last, UUID.randomUUID().toString());
    }

    protected void markCovered(String method, String path) {
        String plain = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        var container = org.springframework.http.server.PathContainer.parsePath(plain);
        // Matched by path pattern and method, so endpoints that only consume multipart (uploads) resolve too.
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            var info = entry.getKey();
            boolean methodMatches = info.getMethodsCondition().getMethods().isEmpty()
                    || info.getMethodsCondition().getMethods().stream().anyMatch(m -> m.name().equals(method));
            boolean pathMatches = info.getPathPatternsCondition() != null
                    && info.getPathPatternsCondition().getPatterns().stream().anyMatch(p -> p.matches(container));
            if (methodMatches && pathMatches) {
                CrossTenantCoverage.markCovered(handlerKey(entry.getValue()));
                return;
            }
        }
        throw new IllegalStateException("Could not resolve the handler for " + method + " " + path);
    }

    static String handlerKey(HandlerMethod handler) {
        return handler.getBeanType().getName() + "#" + handler.getMethod().getName();
    }
}
