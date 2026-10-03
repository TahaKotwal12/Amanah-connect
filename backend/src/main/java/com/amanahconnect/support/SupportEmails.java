package com.amanahconnect.support;

import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.auth.UserStatus;
import com.amanahconnect.community.CommunityUserRepository;
import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * "You have a new message" emails, one per burst. They go to the other side of the conversation: a community's admins when
 * the platform writes, the assigned super admin (or every active one when nobody is assigned) when a community writes.
 * They are platform mail: they carry no community id, so they never use up a community's email quota.
 */
@Component
public class SupportEmails {

    public static final String TEMPLATE = "support-message";

    private final EmailOutboxRepository outbox;
    private final CommunityUserRepository communityUsers;
    private final UserRepository users;
    private final SupportProperties properties;

    public SupportEmails(EmailOutboxRepository outbox, CommunityUserRepository communityUsers, UserRepository users, SupportProperties properties) {
        this.outbox = outbox;
        this.communityUsers = communityUsers;
        this.users = users;
        this.properties = properties;
    }

    /**
     * @param unreadBefore how many messages from the sender's side were already unread before this one
     * @return whether an email was queued
     */
    public boolean onMessage(SupportThread thread, String communityName, SupportSide from, long unreadBefore, String senderName, String body, Instant now) {
        SupportSide to = from.other();
        Instant lastNotified = to == SupportSide.COMMUNITY ? thread.getCommunityNotifiedAt() : thread.getPlatformNotifiedAt();
        boolean startsABurst = unreadBefore == 0;
        boolean reminderDue = lastNotified == null || lastNotified.plus(Duration.ofMinutes(properties.emailReminderMinutes())).isBefore(now);
        if (!startsABurst && !reminderDue) return false;
        Set<String> recipients = recipients(thread, to);
        if (recipients.isEmpty()) return false;
        for (String address : recipients) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("communityName", communityName);
            payload.put("threadId", thread.getId().toString());
            payload.put("subject", thread.getSubject());
            payload.put("from", from.name());
            payload.put("senderName", senderName);
            payload.put("preview", body.length() > 200 ? body.substring(0, 200) + "…" : body);
            payload.put("unread", unreadBefore + 1);
            EmailOutbox mail = new EmailOutbox();
            mail.setCommunityId(null);
            mail.setToEmail(address);
            mail.setTemplate(TEMPLATE);
            mail.setPayload(payload);
            outbox.save(mail);
        }
        if (to == SupportSide.COMMUNITY) thread.setCommunityNotifiedAt(now);
        else thread.setPlatformNotifiedAt(now);
        return true;
    }

    private Set<String> recipients(SupportThread thread, SupportSide to) {
        Set<String> out = new LinkedHashSet<>();
        if (to == SupportSide.COMMUNITY) {
            communityUsers.findByCommunityId(thread.getCommunityId()).forEach(link -> add(out, link.getUser()));
        } else if (thread.getAssignedTo() != null) {
            users.findById(thread.getAssignedTo()).ifPresent(u -> add(out, u));
        }
        if (to == SupportSide.PLATFORM && out.isEmpty()) {
            users.findByRoleAndStatus(UserRole.SUPER_ADMIN, UserStatus.ACTIVE).forEach(u -> add(out, u));
        }
        return out;
    }

    private static void add(Set<String> out, User user) {
        if (user.getStatus() == UserStatus.ACTIVE && user.getEmail() != null) out.add(user.getEmail());
    }
}
