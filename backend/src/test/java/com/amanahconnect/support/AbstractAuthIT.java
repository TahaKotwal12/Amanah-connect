package com.amanahconnect.support;

import com.amanahconnect.auth.Tokens;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Base for auth integration tests: an API client for the running server plus database helpers. */
public abstract class AbstractAuthIT extends AbstractIntegrationTest {

    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected AuthTestUsers users;

    protected ApiClient api;

    @org.junit.jupiter.api.BeforeEach
    void createClient() {
        api = new ApiClient(port);
    }

    /** A signed-in session: the access token and the raw refresh cookie value. */
    public record Session(String accessToken, String refreshToken) {
        public String bearer() {
            return ApiClient.bearer(accessToken);
        }
    }

    protected ApiClient.Response login(TestUser user) {
        return api.post("/api/v1/auth/login", Map.of("email", user.email(), "password", user.password()));
    }

    protected Session loginOk(TestUser user) {
        ApiClient.Response response = login(user);
        if (response.status() != 200 || response.json().get("accessToken") == null) {
            throw new AssertionError("Expected a full login but got " + response.status() + ": " + response.body());
        }
        return new Session(response.json().get("accessToken").asString(), response.cookie("amanah_refresh"));
    }

    protected ApiClient.Response refresh(String refreshToken) {
        return api.post("/api/v1/auth/refresh", null, ApiClient.cookieCall(refreshToken));
    }

    protected List<Map<String, Object>> auditRows(UUID actorId, String action) {
        return jdbc.queryForList(
                "select * from audit_logs where actor_user_id = ? and action = ? order by created_at", actorId, action);
    }

    protected long auditCount(UUID actorId, String action) {
        return auditRows(actorId, action).size();
    }

    protected Map<String, Object> userRow(UUID id) {
        return jdbc.queryForMap("select * from users where id = ?", id);
    }

    protected static String hash(String raw) {
        return Tokens.sha256Hex(raw);
    }

}
