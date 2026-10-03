package com.amanahconnect.mail;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One email the app can send: its name (what is stored in the outbox), who it is for (which decides the header and footer), the
 * subject, the payload keys it cannot be rendered without, and whether its payload carries a secret that must be erased once sent.
 */
public record MailTemplate(String name, Audience audience, Function<Map<String, Object>, String> subject, List<String> required, boolean sensitive) {

    /**
     * MEMBER: a community writing to its member (the community's name and logo, the community's contact details in the footer, replies go to the
     * community). ADMIN: the platform writing to a community's admins about their community. PLATFORM: the platform writing to anyone else.
     */
    public enum Audience { MEMBER, ADMIN, PLATFORM }

    public String subjectFor(Map<String, Object> payload) {
        return subject.apply(payload);
    }
}
