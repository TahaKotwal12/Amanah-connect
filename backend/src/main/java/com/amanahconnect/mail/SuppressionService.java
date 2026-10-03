package com.amanahconnect.mail;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.Masking;
import com.amanahconnect.notification.EmailSuppression;
import com.amanahconnect.notification.EmailSuppressionRepository;
import com.amanahconnect.notification.SuppressionReason;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The suppression list: addresses that bounced for good or complained, which the app never emails again. */
@Service
@Transactional
public class SuppressionService {

    private final EmailSuppressionRepository suppressions;
    private final AuditService audit;

    public SuppressionService(EmailSuppressionRepository suppressions, AuditService audit) {
        this.suppressions = suppressions;
        this.audit = audit;
    }

    /** Adds the address unless it is already listed (the first reason is kept). @return true if it was newly added */
    public boolean suppress(String address, SuppressionReason reason, String source, Map<String, Object> detail) {
        String email = normalise(address);
        if (email == null || suppressions.existsByEmail(email)) return false;
        EmailSuppression entry = new EmailSuppression();
        entry.setEmail(email);
        entry.setReason(reason);
        entry.setSource(source);
        entry.setDetail(detail == null ? new LinkedHashMap<>() : new LinkedHashMap<>(detail));
        suppressions.save(entry);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("email", Masking.email(email));
        after.put("reason", reason.name());
        after.put("source", source);
        audit.record("EMAIL_SUPPRESSED", AuditService.currentActorId(), null, "EmailSuppression", entry.getId(), null, after);
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isSuppressed(String address) {
        String email = normalise(address);
        return email != null && suppressions.existsByEmail(email);
    }

    /** @return true if the address was on the list */
    public boolean remove(String address) {
        String email = normalise(address);
        if (email == null) return false;
        var found = suppressions.findByEmail(email);
        if (found.isEmpty()) return false;
        suppressions.delete(found.get());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("email", Masking.email(email));
        before.put("reason", found.get().getReason().name());
        audit.record("EMAIL_SUPPRESSION_REMOVED", AuditService.currentActorId(), null, "EmailSuppression", found.get().getId(), before, null);
        return true;
    }

    static String normalise(String address) {
        if (address == null) return null;
        String email = address.trim().toLowerCase(Locale.ROOT);
        return email.isEmpty() || email.length() > 320 || !email.contains("@") ? null : email;
    }
}
