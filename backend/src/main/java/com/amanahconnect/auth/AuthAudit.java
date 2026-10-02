package com.amanahconnect.auth;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.Masking;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Records auth events with the user's community and request context filled in. */
@Component
public class AuthAudit {

    private final AuditService audit;
    private final UserSecurityPolicy policy;

    public AuthAudit(AuditService audit, UserSecurityPolicy policy) {
        this.audit = audit;
        this.policy = policy;
    }

    public void event(String action, User user, Map<String, Object> details) {
        audit.record(
                action, user.getId(), policy.communityIdOf(user), "User", user.getId(), null, safe(details));
    }

    /** For attempts against an address that has no account: only the masked address is kept. */
    public void unknownAccount(String action, String attemptedEmail) {
        audit.record(
                action,
                null,
                null,
                "User",
                null,
                null,
                Map.of("attemptedEmail", Masking.email(attemptedEmail), "accountFound", false));
    }

    private static Map<String, Object> safe(Map<String, Object> details) {
        return details == null ? Map.of() : new HashMap<>(details);
    }
}
