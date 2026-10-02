package com.amanahconnect.lead.admin;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.lead.LeadDtos.CommunityDraft;
import com.amanahconnect.lead.LeadDtos.LeadView;
import com.amanahconnect.lead.LeadDtos.UpdateLeadStatusRequest;
import com.amanahconnect.lead.LeadService;
import com.amanahconnect.lead.LeadStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/admin/leads")
@Tag(name = "Admin · Leads", description = "Demo requests from the public site (SUPER_ADMIN, 2FA completed).")
public class AdminLeadController {

    private static final SortWhitelist SORT =
            SortWhitelist.of(
                    Sort.by(Sort.Direction.DESC, "createdAt"),
                    Map.of("createdAt", "createdAt", "name", "name", "status", "status", "communityName", "communityName"));

    private final LeadService service;

    public AdminLeadController(LeadService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List leads", description = "Filter by status, search name, email and community name (q), sort and paginate.")
    public PageResponse<LeadView> list(@RequestParam(required = false) LeadStatus status, @RequestParam(required = false) @Size(max = 100) String q, @Valid PageQuery page) {
        return service.list(status, q, SORT.toPageRequest(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lead detail")
    public LeadView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/{id}/status")
    @AuditHandledBy("LeadService records LEAD_STATUS_CHANGED with before and after")
    @Operation(summary = "Change a lead's status", description = "NEW, CONTACTED, DEMO_SCHEDULED or LOST. CONVERTED is set by creating the community from the lead.")
    public LeadView updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateLeadStatusRequest body) {
        return service.updateStatus(id, body.status());
    }

    @GetMapping("/{id}/community-draft")
    @Operation(summary = "Convert to community (pre-fill)", description = "Returns a create-community request pre-filled from the lead, with leadId set. POST it to /admin/communities (after completing the missing fields) to convert the lead.")
    public CommunityDraft draft(@PathVariable UUID id) {
        return service.draft(id);
    }
}
