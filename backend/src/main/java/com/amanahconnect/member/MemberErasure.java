package com.amanahconnect.member;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.file.FileService;
import com.amanahconnect.tenant.TenantGuard;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The right to erasure for a member. Everything that identifies the person goes: name, email, phone, group, custom fields, the registration
 * form they filled in, the free text of their complaints, the names in emails queued or sent to them, and the stored receipt PDFs that carry their name.
 * Everything financial stays exactly as it was: the member row (as "Erased member", keeping its number), invoices, payments, receipts and ledger
 * entries, so the books still add up and numbering has no gaps. Unpaid invoices remain owed and can still be found by member number.
 *
 * <p>It cannot be undone, so it needs the member's number typed back and a reason. The audit entry keeps the member's id and number, the reason and
 * counts of what was changed; it never holds the erased details. (The audit trail is append-only: do not put personal details in the reason.)
 */
@Service
@Transactional
public class MemberErasure {

    public static final String ERASED_NAME = "Erased member";
    private static final String REMOVED = "[Removed when the member's personal data was erased]";

    public record Request(@NotBlank @Size(max = 30) String memberNo, @NotBlank @Size(max = 300) String reason) {}

    /** What was kept and what was removed. */
    public record Result(UUID memberId, String memberNo, Instant anonymisedAt, Kept kept, Removed removed) {}

    public record Kept(long invoices, long payments, long receipts, long unpaidInvoices, Money outstanding) {}

    public record Removed(long registrations, long complaints, long complaintComments, long emails, long receiptPdfs) {}

    private final MemberRepository members;
    private final NamedParameterJdbcTemplate jdbc;
    private final FileService files;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final EntityManager em;
    private final Clock clock;

    public MemberErasure(MemberRepository members, NamedParameterJdbcTemplate jdbc, FileService files, AuditService audit, TenantGuard tenantGuard, EntityManager em, Clock clock) {
        this.members = members;
        this.jdbc = jdbc;
        this.files = files;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.em = em;
        this.clock = clock;
    }

    public Result anonymise(UUID communityId, UUID memberId, Request request) {
        tenantGuard.requireWritable();
        Member member = tenantGuard.found(members.findWithLockByIdAndCommunityId(memberId, communityId));
        if (member.getAnonymisedAt() != null) {
            throw new ApiException(ErrorCode.MEMBER_ALREADY_ANONYMISED, "This member's personal data was already erased.");
        }
        if (!member.getMemberNo().equalsIgnoreCase(request.memberNo().trim())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("memberNo: type the member's number to confirm the erasure"));
        }
        String reason = Text.singleLine(request.reason());
        if (reason.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("reason: must not be blank"));
        }
        String oldName = member.getFullName();
        String oldEmail = member.getEmail();
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource("c", communityId).addValue("m", memberId).addValue("now", java.sql.Timestamp.from(now));

        Kept kept = kept(p);

        // 1. The member row itself.
        member.setFullName(ERASED_NAME);
        member.setEmail(null);
        member.setPhone(null);
        member.setGroupLabel(null);
        member.setCustomFields(new HashMap<>());
        member.setConsentEmail(false);
        member.setStatus(MemberStatus.INACTIVE);
        member.setStatusReason("Personal data erased");
        member.setStatusChangedAt(now);
        if (member.getDeletedAt() == null) {
            member.setDeletedAt(now);
            member.setDeletedBy(AuditService.currentActorId());
            member.setDeleteReason("Personal data erased");
        }
        member.setAnonymisedAt(now);
        member.setAnonymisedBy(AuditService.currentActorId());
        members.save(member);
        em.flush();

        // 2. Copies of the person's details elsewhere.
        long registrations = jdbc.update(
                "UPDATE member_registrations SET full_name = :name, email = NULL, phone = NULL, group_label = NULL, custom_fields = '{}'::jsonb, consent_email = false, rejection_reason = NULL, anonymised_at = :now"
                        + " WHERE community_id = :c AND member_id = :m", p.addValue("name", ERASED_NAME));
        long complaints = jdbc.update("UPDATE complaints SET subject = 'Complaint (member erased)', description = :removed WHERE community_id = :c AND member_id = :m", p.addValue("removed", REMOVED));
        long comments = jdbc.update("UPDATE complaint_comments SET body = :removed WHERE community_id = :c AND complaint_id IN (SELECT id FROM complaints WHERE community_id = :c AND member_id = :m)", p);
        long emails = scrubEmails(communityId, memberId, oldName, oldEmail);
        long pdfs = removeReceiptPdfs(communityId, p);

        // 3. The record of what was done: ids, numbers and counts only.
        Removed removed = new Removed(registrations, complaints, comments, emails, pdfs);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("memberNo", member.getMemberNo());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("memberNo", member.getMemberNo());
        after.put("reason", reason);
        after.put("kept", kept);
        after.put("removed", removed);
        audit.record("MEMBER_ANONYMISED", "Member", memberId, before, after);
        return new Result(memberId, member.getMemberNo(), now, kept, removed);
    }

    private Kept kept(MapSqlParameterSource p) {
        return jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM invoices WHERE community_id = :c AND member_id = :m) AS invoices,"
                        + " (SELECT count(*) FROM payment_records WHERE community_id = :c AND member_id = :m) AS payments,"
                        + " (SELECT count(*) FROM receipts r JOIN payment_records pr ON pr.id = r.payment_record_id AND pr.community_id = r.community_id WHERE r.community_id = :c AND pr.member_id = :m) AS receipts,"
                        + " (SELECT count(*) FROM invoices WHERE community_id = :c AND member_id = :m AND status IN ('ISSUED', 'PARTIAL', 'OVERDUE')) AS unpaid,"
                        + " (SELECT coalesce(sum(amount - amount_paid), 0) FROM invoices WHERE community_id = :c AND member_id = :m AND status IN ('ISSUED', 'PARTIAL', 'OVERDUE')) AS outstanding",
                p, (rs, n) -> new Kept(rs.getLong("invoices"), rs.getLong("payments"), rs.getLong("receipts"), rs.getLong("unpaid"), Money.of(rs.getBigDecimal("outstanding"))));
    }

    /**
     * Mail queued or sent to the member: pending ones are cancelled, and the names and links inside all of them are wiped. When another live member
     * of the community uses the same address (allowed, with a warning), only mail that names this member is touched.
     */
    private long scrubEmails(UUID communityId, UUID memberId, String oldName, String oldEmail) {
        if (oldEmail == null) return 0;
        boolean shared = members.findFirstByCommunityIdAndEmailAndDeletedAtIsNullAndIdNot(communityId, oldEmail, memberId).isPresent();
        MapSqlParameterSource p = new MapSqlParameterSource("c", communityId).addValue("email", oldEmail).addValue("name", oldName);
        String match = "community_id = :c AND to_email = :email" + (shared ? " AND (payload ->> 'memberName' = :name OR payload ->> 'recipientName' = :name)" : "");
        jdbc.update("UPDATE email_outbox SET status = 'FAILED', error = 'Cancelled: the recipient''s personal data was erased' WHERE " + match + " AND status = 'PENDING'", p);
        return jdbc.update("UPDATE email_outbox SET payload = '{\"erased\": true}'::jsonb, to_email = 'erased-' || substr(id::text, 1, 8) || '@erased.invalid' WHERE " + match, p);
    }

    /** The stored receipt PDFs carry the member's name. They are re-created on request from the (now anonymised) data. */
    private long removeReceiptPdfs(UUID communityId, MapSqlParameterSource p) {
        List<String> keys = jdbc.queryForList(
                "SELECT r.pdf_key FROM receipts r JOIN payment_records pr ON pr.id = r.payment_record_id AND pr.community_id = r.community_id"
                        + " WHERE r.community_id = :c AND pr.member_id = :m AND r.pdf_key IS NOT NULL", p, String.class);
        if (keys.isEmpty()) return 0;
        jdbc.update("UPDATE receipts SET pdf_key = NULL WHERE community_id = :c AND payment_record_id IN (SELECT id FROM payment_records WHERE community_id = :c AND member_id = :m)", p);
        for (String key : keys) files.delete(communityId, key);
        return keys.size();
    }
}
