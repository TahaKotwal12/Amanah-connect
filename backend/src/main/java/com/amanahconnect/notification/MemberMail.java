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
        return member == null ? Delivery.NO_ADDRESS : sendTo(community, member.getEmail(), member.isConsentEmail(), template, payload);
    }

    /** For someone who is not (yet) a member, such as a person who registered through an invite link. */
    public Delivery sendTo(Community community, String address, boolean consent, String template, Map<String, Object> payload) {
        if (address == null || address.isBlank()) return Delivery.NO_ADDRESS;
        if (!consent) return Delivery.NO_CONSENT;
        if (planLimits.emailQuotaRemaining(community.getId()) <= 0) return Delivery.QUOTA;
        EmailOutbox mail = new EmailOutbox();
        mail.setCommunityId(community.getId());
        mail.setToEmail(address);
        mail.setTemplate(template);
        mail.setPayload(payload);
        outbox.save(mail);
        return Delivery.QUEUED;
    }
}
