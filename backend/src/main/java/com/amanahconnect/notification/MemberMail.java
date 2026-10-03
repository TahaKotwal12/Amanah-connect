package com.amanahconnect.notification;

import com.amanahconnect.community.Community;
import com.amanahconnect.member.Member;
import com.amanahconnect.plan.PlanLimitService;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * One email to one member from a community admin's action (a complaint update, a test). It goes only to a member who
 * has an address and has agreed to email, and counts against the community's monthly email quota; when any of that is
 * not so, nothing is queued and the caller is told why. The action itself never fails because of email.
 */
@Component
public class MemberMail {

    private final EmailOutboxRepository outbox;
    private final PlanLimitService planLimits;

    public MemberMail(EmailOutboxRepository outbox, PlanLimitService planLimits) {
        this.outbox = outbox;
        this.planLimits = planLimits;
    }

    public Delivery send(Community community, Member member, String template, Map<String, Object> payload) {
        if (member == null || member.getEmail() == null) return Delivery.NO_ADDRESS;
        if (!member.isConsentEmail()) return Delivery.NO_CONSENT;
        if (planLimits.emailQuotaRemaining(community.getId()) <= 0) return Delivery.QUOTA;
        EmailOutbox mail = new EmailOutbox();
        mail.setCommunityId(community.getId());
        mail.setToEmail(member.getEmail());
        mail.setTemplate(template);
        mail.setPayload(payload);
        outbox.save(mail);
        return Delivery.QUEUED;
    }
}
