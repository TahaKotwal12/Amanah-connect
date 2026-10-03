package com.amanahconnect.member;

import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.notification.NotificationSettingsRepository;
import com.amanahconnect.plan.PlanLimitService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Emails to members, queued in the outbox on the community's own quota. */
@Component
public class MemberEmails {

    public static final String WELCOME = "member-welcome";

    private static final Logger log = LoggerFactory.getLogger(MemberEmails.class);

    private final EmailOutboxRepository outbox;
    private final NotificationSettingsRepository settings;
    private final PlanLimitService planLimits;

    public MemberEmails(EmailOutboxRepository outbox, NotificationSettingsRepository settings, PlanLimitService planLimits) {
        this.outbox = outbox;
        this.settings = settings;
        this.planLimits = planLimits;
    }

    /**
     * Queues the welcome email if the community has it switched on and the member has an address and has agreed to
     * email. Over the plan's email quota the welcome is skipped (with a warning), never the member's creation.
     *
     * @return whether an email was queued
     */
    public boolean welcome(UUID communityId, String communityName, Member member) {
        if (member.getEmail() == null || !member.isConsentEmail() || member.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        boolean enabled = settings.findByCommunityId(communityId).map(s -> s.isSendWelcome()).orElse(true);
        if (!enabled) {
            return false;
        }
        if (!planLimits.hasEmailQuota(communityId, 1)) {
            log.warn("Welcome email skipped for community {}: the email quota is used up", communityId);
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("communityName", communityName);
        payload.put("memberName", member.getFullName());
        payload.put("memberNo", member.getMemberNo());
        EmailOutbox email = new EmailOutbox();
        email.setCommunityId(communityId);
        email.setToEmail(member.getEmail());
        email.setTemplate(WELCOME);
        email.setPayload(payload);
        outbox.save(email);
        return true;
    }
}
