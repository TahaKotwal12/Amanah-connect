package com.amanahconnect.lead;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.admin.AdminCommunityDtos.CreateCommunityRequest;
import com.amanahconnect.lead.LeadDtos.CommunityDraft;
import com.amanahconnect.lead.LeadDtos.LeadView;
import com.amanahconnect.lead.LeadDtos.PublicLeadRequest;
import com.amanahconnect.notification.PlatformEmails;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.plan.PlanRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);
    /** A visitor who submits twice gets one acknowledgement an hour, not two. */
    static final Duration ACK_WINDOW = Duration.ofHours(1);

    private final LeadRepository leads;
    private final PlanRepository plans;
    private final PlatformEmails emails;
    private final AuditService audit;

    public LeadService(LeadRepository leads, PlanRepository plans, PlatformEmails emails, AuditService audit) {
        this.leads = leads;
        this.plans = plans;
        this.emails = emails;
        this.audit = audit;
    }

    // ---- public form ------------------------------------------------------------------------------

    public void receive(PublicLeadRequest request) {
        if (request.website() != null && !request.website().isBlank()) {
            // The honeypot: a bot filled the hidden field. Answer exactly like a success, store and send nothing.
            log.info("Lead form honeypot triggered; submission dropped");
            return;
        }
        String name = singleLine(request.name());
        String email = singleLine(request.email());
        if (name.isEmpty() || email.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", java.util.List.of("name and email are required"));
        }
        Lead lead = new Lead();
        lead.setName(name);
        lead.setEmail(email);
        lead.setPhone(blankToNull(singleLine(request.phone())));
        lead.setCommunityName(blankToNull(singleLine(request.communityName())));
        lead.setSizeEstimate(request.size());
        lead.setMessage(blankToNull(multiLine(request.message())));
        leads.save(lead);

        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("leadId", lead.getId().toString());
        notification.put("name", lead.getName());
        notification.put("email", lead.getEmail());
        notification.put("phone", lead.getPhone());
        notification.put("communityName", lead.getCommunityName());
        notification.put("size", lead.getSizeEstimate());
        notification.put("message", lead.getMessage());
        emails.toSuperAdmins(PlatformEmails.LEAD_NOTIFICATION, notification);

        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("name", lead.getName());
        ack.put("communityName", lead.getCommunityName());
        emails.toAddressOncePer(PlatformEmails.LEAD_ACKNOWLEDGEMENT, lead.getEmail(), ack, ACK_WINDOW);

        // No PII in the audit entry: the lead row holds it.
        audit.record("LEAD_RECEIVED", null, null, "Lead", lead.getId(), null, Map.of("source", lead.getSource()));
    }

    // ---- super admin ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<LeadView> list(LeadStatus status, String q, Pageable pageable) {
        String needle = q == null || q.isBlank() ? null : "%" + escapeLike(q.trim().toLowerCase()) + "%";
        Page<Lead> page = leads.search(status, needle, pageable);
        return PageResponse.from(page, LeadView::of);
    }

    @Transactional(readOnly = true)
    public LeadView get(UUID id) {
        return LeadView.of(find(id));
    }

    public LeadView updateStatus(UUID id, LeadStatus status) {
        Lead lead = find(id);
        if (status == LeadStatus.CONVERTED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "A lead becomes CONVERTED by creating its community.");
        }
        if (lead.getStatus() == LeadStatus.CONVERTED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This lead has already been converted to a community.");
        }
        Map<String, Object> before = Map.of("status", lead.getStatus().name());
        lead.setStatus(status);
        lead.setHandledBy(AuditService.currentActorId());
        audit.record("LEAD_STATUS_CHANGED", "Lead", id, before, Map.of("status", status.name()));
        return LeadView.of(lead);
    }

    @Transactional(readOnly = true)
    public CommunityDraft draft(UUID id) {
        Lead lead = find(id);
        if (lead.getStatus() == LeadStatus.CONVERTED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This lead has already been converted to a community.");
        }
        Plan suggested = suggestPlan(lead.getSizeEstimate());
        CreateCommunityRequest request = new CreateCommunityRequest(
                lead.getCommunityName() == null ? lead.getName() : lead.getCommunityName(), null,
                lead.getName(), lead.getEmail(), lead.getPhone(),
                null, null, null, null, null, null, null,
                suggested == null ? null : suggested.getId(), null,
                lead.getName(), lead.getEmail(), lead.getId());
        return new CommunityDraft(request, suggested == null ? null : suggested.getCode());
    }

    /** The cheapest public plan whose member limit fits the lead's size; null when none does. */
    private Plan suggestPlan(Integer size) {
        for (Plan plan : plans.findByPublicPlanTrueAndActiveTrueOrderBySortOrderAsc()) {
            Object max = plan.getLimits() == null ? null : plan.getLimits().get("max_members");
            if (size == null || max == null || (max instanceof Number n && n.longValue() >= size)) {
                return plan;
            }
        }
        return null;
    }

    private Lead find(UUID id) {
        return leads.findById(id).orElseThrow(NotFoundException::new);
    }

    // ---- text hygiene -------------------------------------------------------------------------------

    /** Single-line text: control characters (including newlines) become spaces, runs collapse, ends trimmed. */
    static String singleLine(String value) {
        return com.amanahconnect.common.Text.singleLine(value);
    }

    /** Free text: newlines and tabs survive, other control characters are dropped. */
    static String multiLine(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "").replaceAll("\\p{Cf}", "").trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
