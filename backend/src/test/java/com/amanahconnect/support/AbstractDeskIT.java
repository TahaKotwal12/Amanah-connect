package com.amanahconnect.support;

import com.amanahconnect.auth.JwtService;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.billing.AbstractFinanceIT;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** Two communities with admins (from the tenant harness) plus a super admin, for the helpdesk and announcements tests. */
public abstract class AbstractDeskIT extends AbstractFinanceIT {

    protected static final String ADMIN = "/api/v1/admin";

    @Autowired protected JwtService jwtService;
    @Autowired protected UserRepository userRepository;

    protected AuthTestUsers.TestUser superAdmin;
    protected String superToken;

    @BeforeEach
    void createSuperAdmin() {
        superAdmin = users.superAdmin();
        superToken = jwtService.issueAccessToken(userRepository.findById(superAdmin.id()).orElseThrow(), false);
    }

    protected ApiClient.Response asSuper(String method, String path, Object body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Authorization";
        all[1] = ApiClient.bearer(superToken);
        System.arraycopy(headers, 0, all, 2, headers.length);
        return api.call(method, path, body, all);
    }

    protected Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}
