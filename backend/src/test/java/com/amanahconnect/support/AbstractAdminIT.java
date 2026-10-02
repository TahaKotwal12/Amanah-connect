package com.amanahconnect.support;

import com.amanahconnect.auth.JwtService;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** Base for super-admin tests: a SUPER_ADMIN whose 2FA is complete (no mfa_setup_required), plus helpers. */
public abstract class AbstractAdminIT extends AbstractAuthIT {

    protected static final String ADMIN = "/api/v1/admin";

    @Autowired protected JwtService jwtService;
    @Autowired protected UserRepository userRepository;
    @Autowired protected TestData data;

    protected TestUser superAdmin;
    protected String superToken;

    @BeforeEach
    void createSuperAdmin() {
        superAdmin = users.superAdmin();
        superToken = tokenFor(superAdmin, false);
    }

    protected String tokenFor(TestUser user, boolean mfaSetupRequired) {
        return jwtService.issueAccessToken(userRepository.findById(user.id()).orElseThrow(), mfaSetupRequired);
    }

    protected ApiClient.Response admin(String method, String path, Object body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Authorization";
        all[1] = ApiClient.bearer(superToken);
        System.arraycopy(headers, 0, all, 2, headers.length);
        return api.call(method, path, body, all);
    }

    protected ApiClient.Response adminGet(String path) {
        return admin("GET", path, null);
    }

    protected ApiClient.Response adminPost(String path, Object body) {
        return admin("POST", path, body);
    }

    /** A valid create-community request body; override fields by passing a map to merge. */
    protected Map<String, Object> newCommunityBody(Plan plan, String name, String ownerEmail) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("contactName", "Contact Person");
        body.put("contactEmail", "contact-" + TestData.unique() + "@example.test");
        body.put("contactPhone", "+91 98765 43210");
        body.put("addressLine1", "12 Garden Road");
        body.put("city", "Pune");
        body.put("state", "Maharashtra");
        body.put("postalCode", "411001");
        body.put("country", "IN");
        body.put("dateOfEstablishment", "2010-04-01");
        body.put("planId", plan.getId().toString());
        body.put("ownerName", "Owner Person");
        body.put("ownerEmail", ownerEmail);
        return body;
    }

    protected Plan starter() {
        return data.plan("STARTER");
    }

    protected UUID createCommunityViaApi(String name) {
        String ownerEmail = "owner-" + TestData.unique() + "@example.test";
        ApiClient.Response response = adminPost(ADMIN + "/communities", newCommunityBody(starter(), name, ownerEmail));
        if (response.status() != 201) {
            throw new AssertionError("create failed: " + response.status() + " " + response.body());
        }
        return UUID.fromString(response.json().get("id").asString());
    }

    protected List<Map<String, Object>> emailsTo(String address, String template) {
        return jdbc.queryForList("select * from email_outbox where to_email = ? and template = ? order by created_at", address, template);
    }

    /** The raw token exists only in the emailed link; read it back from the outbox like the mailer would. */
    protected String tokenFromEmail(String template, String address) {
        List<Map<String, Object>> rows = emailsTo(address, template);
        if (rows.isEmpty()) {
            throw new AssertionError("no " + template + " email queued for " + address);
        }
        Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(rows.get(rows.size() - 1).get("payload").toString());
        if (!matcher.find()) {
            throw new AssertionError("no token in payload");
        }
        return matcher.group(1);
    }

    protected UUID insertSubscription(Community community, Plan plan, LocalDate start, LocalDate end, String status, String reference, String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into platform_subscriptions (id, community_id, plan_id, period_start, period_end, amount, reference, status, recorded_by, paid_on, cancel_reason)"
                        + " values (?, ?, ?, ?, ?, ?::numeric, ?, ?, ?, ?, ?)",
                id, community.getId(), plan.getId(), start, end, amount, reference, status, superAdmin.id(), start,
                "CANCELLED".equals(status) ? "test" : null);
        return id;
    }
}
