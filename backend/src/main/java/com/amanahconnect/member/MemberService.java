package com.amanahconnect.member;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.csv.CsvWriter;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.member.MemberDtos.ActivateRequest;
import com.amanahconnect.member.MemberDtos.CreateMemberRequest;
import com.amanahconnect.member.MemberDtos.DeactivateRequest;
import com.amanahconnect.member.MemberDtos.MemberCounts;
import com.amanahconnect.member.MemberDtos.MemberFilter;
import com.amanahconnect.member.MemberDtos.MemberView;
import com.amanahconnect.member.MemberDtos.UnpaidSummary;
import com.amanahconnect.member.MemberDtos.UpdateMemberRequest;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.tenant.TenantGuard;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * A community's members. Every method takes the community id from the authenticated principal (the controller
 * passes it) and looks rows up with it, so another community's member is simply "not found". Members are never
 * hard-deleted. Audit entries carry ids, status and flags, not names, emails or phone numbers.
 */
@Service
@Transactional
public class MemberService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int EXPORT_PAGE = 500;

    /** The data for a new member, from the admin's form or an approved registration. */
    public record NewMember(String fullName, String email, String phone, String group, LocalDate joinedOn, Map<String, Object> customFields, boolean consentEmail, String source) {}

    private final MemberRepository members;
    private final CommunityRepository communities;
    private final MemberNumbers numbers;
    private final MemberLock lock;
    private final MemberEmails emails;
    private final PlanLimitService planLimits;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final JsonMapper json;
    private final Clock clock;

    public MemberService(
            MemberRepository members,
            CommunityRepository communities,
            MemberNumbers numbers,
            MemberLock lock,
            MemberEmails emails,
            PlanLimitService planLimits,
            NamedParameterJdbcTemplate jdbc,
            AuditService audit,
            TenantGuard tenantGuard,
            JsonMapper json,
            Clock clock) {
        this.members = members;
        this.communities = communities;
        this.numbers = numbers;
        this.lock = lock;
        this.emails = emails;
        this.planLimits = planLimits;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.json = json;
        this.clock = clock;
    }

    // ---- reads --------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<MemberView> list(UUID communityId, MemberFilter filter, Pageable pageable) {
        return PageResponse.from(search(communityId, filter, pageable), MemberView::of);
    }

    @Transactional(readOnly = true)
    public MemberView get(UUID communityId, UUID id) {
        return MemberView.of(find(communityId, id));
    }

    @Transactional(readOnly = true)
    public MemberCounts counts(UUID communityId) {
        LocalDate today = LocalDate.now(clock.withZone(IST));
        var monthStart = today.withDayOfMonth(1).atStartOfDay(IST).toInstant();
        Long pending = jdbc.queryForObject("SELECT count(*) FROM member_registrations WHERE community_id = :c AND status = 'PENDING'", new MapSqlParameterSource("c", communityId), Long.class);
        return new MemberCounts(
                members.countByCommunityIdAndDeletedAtIsNull(communityId),
                members.countByCommunityIdAndDeletedAtIsNullAndStatus(communityId, MemberStatus.ACTIVE),
                members.countByCommunityIdAndDeletedAtIsNullAndStatus(communityId, MemberStatus.INACTIVE),
                members.countByCommunityIdAndDeletedAtIsNullAndCreatedAtGreaterThanEqual(communityId, monthStart),
                pending == null ? 0 : pending);
    }

    /** Streams the filtered members as CSV, a page at a time, so a large community never sits in memory. Returns the row count. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public long exportCsv(UUID communityId, MemberFilter filter, Writer out) throws IOException {
        out.write('﻿'); // lets Excel read the names as UTF-8
        out.write(new CsvWriter().row("member_no", "full_name", "email", "phone", "group", "status", "joined_on", "consent_email", "created_at", "custom_fields").toString());
        long count = 0;
        for (int page = 0; ; page++) {
            Page<Member> batch = search(communityId, filter, PageRequest.of(page, EXPORT_PAGE, Sort.by("createdAt", "id")));
            for (Member m : batch.getContent()) {
                out.write(new CsvWriter().row(
                        m.getMemberNo(), m.getFullName(), m.getEmail(), m.getPhone(), m.getGroupLabel(), m.getStatus(),
                        m.getJoinedOn(), m.isConsentEmail(), m.getCreatedAt(), m.getCustomFields().isEmpty() ? "" : json.writeValueAsString(m.getCustomFields())).toString());
                count++;
            }
            out.flush();
            if (!batch.hasNext()) {
                break;
            }
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("count", count);
        after.put("status", filter.status() == null ? null : filter.status().name());
        after.put("group", filter.group());
        after.put("searched", filter.q() != null && !filter.q().isBlank());
        audit.record("MEMBERS_EXPORTED", "Member", null, null, after);
        return count;
    }

    // ---- create ----------------------------------------------------------------------------------------------

    public MemberView create(UUID communityId, CreateMemberRequest request) {
        NewMember data = new NewMember(
                request.fullName().trim(), blankToNull(request.email()), blankToNull(request.phone()), blankToNull(request.group()), request.joinedOn(),
                CustomFields.validate(request.customFields()), Boolean.TRUE.equals(request.consentEmail()), "ADMIN");
        return MemberView.of(add(communityId, data, Boolean.TRUE.equals(request.allowDuplicateEmail())));
    }

    /**
     * Adds a member: takes the community's member lock, checks the plan limit and duplicate email, numbers it, saves
     * it, audits it and queues the welcome email. Also used when a registration is approved.
     */
    public Member add(UUID communityId, NewMember data, boolean allowDuplicateEmail) {
        lock.lock(communityId);
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        planLimits.checkMemberLimit(communityId);
        if (data.email() != null && !allowDuplicateEmail) {
            requireEmailFree(communityId, data.email(), null);
        }
        Member member = new Member();
        member.setCommunityId(communityId);
        member.setMemberNo(numbers.next(communityId, community.getSlug()));
        member.setFullName(data.fullName());
        member.setEmail(data.email());
        member.setPhone(data.phone());
        member.setGroupLabel(data.group());
        member.setJoinedOn(data.joinedOn() == null ? LocalDate.now(clock.withZone(IST)) : data.joinedOn());
        member.setCustomFields(data.customFields());
        member.setConsentEmail(data.consentEmail());
        members.save(member);
        audit.record("MEMBER_CREATED", "Member", member.getId(), null, snapshot(member, data.source()));
        emails.welcome(communityId, community.getName(), member);
        return member;
    }

    // ---- update ----------------------------------------------------------------------------------------------

    public MemberView update(UUID communityId, UUID id, UpdateMemberRequest request) {
        Member member = find(communityId, id);
        Map<String, Object> before = snapshot(member, null);
        if (request.fullName() != null) {
            if (request.fullName().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("fullName: must not be blank"));
            }
            member.setFullName(request.fullName().trim());
        }
        if (request.email() != null) {
            String email = blankToNull(request.email());
            if (email != null && !email.equalsIgnoreCase(member.getEmail()) && !Boolean.TRUE.equals(request.allowDuplicateEmail())) {
                requireEmailFree(communityId, email, member.getId());
            }
            member.setEmail(email);
        }
        if (request.phone() != null) member.setPhone(blankToNull(request.phone()));
        if (request.group() != null) member.setGroupLabel(blankToNull(request.group()));
        if (request.joinedOn() != null) member.setJoinedOn(request.joinedOn());
        if (request.customFields() != null) member.setCustomFields(CustomFields.validate(request.customFields()));
        if (request.consentEmail() != null) member.setConsentEmail(request.consentEmail());
        members.save(member);
        audit.record("MEMBER_UPDATED", "Member", id, before, snapshot(member, null));
        return MemberView.of(member);
    }

    public MemberView deactivate(UUID communityId, UUID id, DeactivateRequest request) {
        return changeStatus(communityId, id, MemberStatus.INACTIVE, request.reason().trim(), "MEMBER_DEACTIVATED");
    }

    public MemberView activate(UUID communityId, UUID id, ActivateRequest request) {
        String reason = request == null ? null : blankToNull(request.reason());
        return changeStatus(communityId, id, MemberStatus.ACTIVE, reason, "MEMBER_ACTIVATED");
    }

    private MemberView changeStatus(UUID communityId, UUID id, MemberStatus target, String reason, String action) {
        Member member = find(communityId, id);
        if (member.getStatus() == target) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This member is already " + target.name().toLowerCase() + ".");
        }
        Map<String, Object> before = snapshot(member, null);
        member.setStatus(target);
        member.setStatusReason(reason);
        member.setStatusChangedAt(clock.instant());
        members.save(member);
        Map<String, Object> after = snapshot(member, null);
        after.put("reason", reason);
        audit.record(action, "Member", id, before, after);
        return MemberView.of(member);
    }

    // ---- delete ----------------------------------------------------------------------------------------------

    /**
     * Soft delete. A member with unpaid invoices cannot be deleted unless {@code force} is set with a reason: the
     * invoices stay (nothing financial is removed) and the forced deletion is audited with what was outstanding.
     */
    public void delete(UUID communityId, UUID id, boolean force, String reason) {
        tenantGuard.requireWritable();
        Member member = find(communityId, id);
        UnpaidSummary unpaid = unpaid(communityId, id);
        String cleanReason = blankToNull(reason);
        if (unpaid.invoices() > 0) {
            if (!force) {
                throw new ApiException(ErrorCode.MEMBER_HAS_UNPAID_INVOICES,
                        "This member has %d unpaid invoice(s) totalling %s. Settle them, or delete with force=true and a reason.".formatted(unpaid.invoices(), unpaid.outstanding()),
                        List.of(), Map.of("unpaidInvoices", unpaid.invoices(), "outstanding", unpaid.outstanding().toString()));
            }
            if (cleanReason == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("reason: required when deleting a member with unpaid invoices"));
            }
        }
        Map<String, Object> before = snapshot(member, null);
        member.setDeletedAt(clock.instant());
        member.setDeletedBy(AuditService.currentActorId());
        member.setDeleteReason(cleanReason);
        members.save(member);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deleted", true);
        after.put("forced", unpaid.invoices() > 0);
        after.put("reason", cleanReason);
        after.put("unpaidInvoices", unpaid.invoices());
        after.put("outstanding", unpaid.outstanding().toString());
        audit.record("MEMBER_DELETED", "Member", id, before, after);
    }

    UnpaidSummary unpaid(UUID communityId, UUID memberId) {
        return jdbc.queryForObject(
                "SELECT count(*) AS n, coalesce(sum(amount - amount_paid), 0) AS outstanding FROM invoices"
                        + " WHERE community_id = :c AND member_id = :m AND status IN ('ISSUED', 'PARTIAL', 'OVERDUE')",
                new MapSqlParameterSource("c", communityId).addValue("m", memberId),
                (rs, row) -> new UnpaidSummary(rs.getLong("n"), Money.of(rs.getBigDecimal("outstanding") == null ? BigDecimal.ZERO : rs.getBigDecimal("outstanding"))));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Whether another live member of the community already uses the address, for the admin's information. */
    @Transactional(readOnly = true)
    public Member emailOwner(UUID communityId, String email) {
        if (email == null) return null;
        return members.findFirstByCommunityIdAndEmailAndDeletedAtIsNullAndIdNot(communityId, email, new UUID(0, 0)).orElse(null);
    }

    public void requireEmailFree(UUID communityId, String email, UUID exceptId) {
        members.findFirstByCommunityIdAndEmailAndDeletedAtIsNullAndIdNot(communityId, email, exceptId == null ? new UUID(0, 0) : exceptId).ifPresent(other -> {
            throw new ApiException(ErrorCode.DUPLICATE_EMAIL,
                    "Member " + other.getMemberNo() + " already uses this email address. To share it on purpose, send allowDuplicateEmail=true.",
                    List.of(), Map.of("existingMemberNo", other.getMemberNo()));
        });
    }

    private Page<Member> search(UUID communityId, MemberFilter filter, Pageable pageable) {
        String q = filter.q() == null ? "" : filter.q().trim().toLowerCase();
        String pattern = q.isEmpty() ? null : "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String digitsOnly = q.replaceAll("[^0-9]", "");
        String digits = !q.isEmpty() && digitsOnly.length() >= 3 && q.matches("^[0-9+()\\-. ]+$") ? "%" + digitsOnly + "%" : null;
        String group = filter.group() == null || filter.group().isBlank() ? null : filter.group().trim().toLowerCase();
        return members.searchByCommunityId(communityId, filter.status(), group, pattern, digits, pageable);
    }

    private Member find(UUID communityId, UUID id) {
        return tenantGuard.found(members.findByIdAndCommunityIdAndDeletedAtIsNull(id, communityId));
    }

    private static Map<String, Object> snapshot(Member m, String source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("memberNo", m.getMemberNo());
        map.put("status", m.getStatus() == null ? null : m.getStatus().name());
        map.put("group", m.getGroupLabel());
        map.put("joinedOn", m.getJoinedOn() == null ? null : m.getJoinedOn().toString());
        map.put("consentEmail", m.isConsentEmail());
        map.put("hasEmail", m.getEmail() != null);
        map.put("hasPhone", m.getPhone() != null);
        map.put("customFieldKeys", m.getCustomFields() == null ? List.of() : List.copyOf(m.getCustomFields().keySet()));
        if (source != null) map.put("source", source);
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
