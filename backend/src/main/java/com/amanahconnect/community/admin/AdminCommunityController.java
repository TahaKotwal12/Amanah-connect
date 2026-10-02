package com.amanahconnect.community.admin;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.community.CommunityStatus;
import com.amanahconnect.community.admin.AdminCommunityDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/admin/communities")
@Tag(name = "Admin · Communities", description = "Create, find, change, suspend and support communities (SUPER_ADMIN, 2FA completed).")
public class AdminCommunityController {

    private static final SortWhitelist SORT =
            SortWhitelist.of(
                    Sort.by(Sort.Direction.DESC, "createdAt"),
                    Map.of("name", "name", "createdAt", "createdAt", "status", "status", "plan", "plan", "memberCount", "memberCount", "subscriptionEndsOn", "subscriptionEndsOn"));

    private final AdminCommunityService service;

    public AdminCommunityController(AdminCommunityService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List communities", description = "Search (q), filter by status and plan code, sort and paginate. Includes member counts and subscription expiry.")
    public PageResponse<CommunityListItem> list(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) CommunityStatus status,
            @RequestParam(required = false) @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9_]*$") String plan,
            @Valid PageQuery page) {
        return service.list(
                new AdminCommunityQueries.Filter(q, status == null ? null : status.name(), plan == null || plan.isBlank() ? null : plan.toUpperCase()),
                SORT.toPageRequest(page));
    }

    @PostMapping
    @AuditHandledBy("AdminCommunityService records COMMUNITY_CREATED (and LEAD_CONVERTED, INVITATION_CREATED)")
    @Operation(summary = "Create a community", description = "Creates the community (PENDING), its owner (INVITED) and queues the set-your-password invitation email.")
    public ResponseEntity<CommunityView> create(@Valid @RequestBody CreateCommunityRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Community detail")
    public CommunityView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    @AuditHandledBy("AdminCommunityService records COMMUNITY_UPDATED with before and after")
    @Operation(summary = "Update a community", description = "Partial update; send the version you read to detect concurrent edits (409 VERSION_CONFLICT).")
    public CommunityView update(@PathVariable UUID id, @Valid @RequestBody UpdateCommunityRequest body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/suspend")
    @AuditHandledBy("AdminCommunityService records COMMUNITY_SUSPENDED and emails the owner")
    @Operation(summary = "Suspend", description = "The community becomes read-only (writes answer 403 COMMUNITY_SUSPENDED). The owner is emailed.")
    public CommunityView suspend(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest body) {
        return service.suspend(id, body.reason());
    }

    @PostMapping("/{id}/activate")
    @AuditHandledBy("AdminCommunityService records COMMUNITY_ACTIVATED and emails the owner")
    @Operation(summary = "Activate", description = "Activates a PENDING or SUSPENDED community (or restores an archived one). The owner is emailed.")
    public CommunityView activate(@PathVariable UUID id, @Valid @RequestBody(required = false) OptionalReasonRequest body) {
        return service.activate(id, body == null ? null : body.reason());
    }

    @PostMapping("/{id}/archive")
    @AuditHandledBy("AdminCommunityService records COMMUNITY_ARCHIVED")
    @Operation(summary = "Archive (soft)", description = "Nothing is deleted; the community becomes read-only.")
    public CommunityView archive(@PathVariable UUID id, @Valid @RequestBody StatusChangeRequest body) {
        return service.archive(id, body.reason());
    }

    @PostMapping("/{id}/reset-admin-password")
    @AuditHandledBy("AdminCommunityService records COMMUNITY_ADMIN_PASSWORD_RESET; PasswordService records the reset request")
    @Operation(summary = "Reset the community admin's password", description = "Queues a reset email and signs the admin out everywhere. For an admin who has not accepted yet, resends the invitation.")
    public AdminCommunityService.ResetOutcome resetAdminPassword(@PathVariable UUID id, @RequestBody(required = false) ResetAdminPasswordRequest body) {
        return service.resetAdminPassword(id, body == null ? null : body.userId());
    }

    @GetMapping("/{id}/export")
    @Operation(summary = "Export community details", description = "format=json (default) or csv: profile, owner, member count and subscription history. Audited.")
    public ResponseEntity<?> export(@PathVariable UUID id, @RequestParam(defaultValue = "json") @Pattern(regexp = "json|csv") String format) {
        CommunityExport export = service.export(id);
        if ("csv".equals(format)) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("community-" + export.profile().slug() + ".csv").build().toString())
                    .body(CommunityExportCsv.toCsv(export));
        }
        return ResponseEntity.ok(export);
    }

    @GetMapping("/{id}/overview")
    @Operation(summary = "Read-only overview for support", description = "Profile, counts, finance summary and recent activity. Every call is audited as SUPPORT_VIEW.")
    public CommunityOverview overview(@PathVariable UUID id) {
        return service.overview(id);
    }

    @GetMapping("/{id}/subscriptions")
    @Operation(summary = "Subscription history of a community")
    public List<SubscriptionLine> subscriptions(@PathVariable UUID id) {
        return service.subscriptionHistory(id);
    }
}
