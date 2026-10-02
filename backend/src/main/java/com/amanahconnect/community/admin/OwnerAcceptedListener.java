package com.amanahconnect.community.admin;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.InvitationAccepted;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityStatus;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A community stays PENDING until its owner accepts the invitation; then it becomes ACTIVE. Runs in the
 * accept-invite transaction, so both happen or neither does.
 */
@Component
public class OwnerAcceptedListener {

    private final CommunityRepository communities;
    private final AuditService audit;
    private final Clock clock;

    public OwnerAcceptedListener(CommunityRepository communities, AuditService audit, Clock clock) {
        this.communities = communities;
        this.audit = audit;
        this.clock = clock;
    }

    @EventListener
    public void onInvitationAccepted(InvitationAccepted event) {
        for (Community community : communities.findByOwnerIdAndStatus(event.userId(), CommunityStatus.PENDING)) {
            community.setStatus(CommunityStatus.ACTIVE);
            community.setStatusReason("The owner accepted the invitation.");
            community.setStatusChangedAt(clock.instant());
            community.setStatusChangedBy(event.userId());
            audit.record(
                    "COMMUNITY_ACTIVATED",
                    event.userId(),
                    community.getId(),
                    "Community",
                    community.getId(),
                    Map.of("status", "PENDING"),
                    Map.of("status", "ACTIVE", "reason", "owner accepted the invitation"));
        }
    }
}
