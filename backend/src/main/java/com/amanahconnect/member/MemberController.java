package com.amanahconnect.member;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.member.MemberDtos.ActivateRequest;
import com.amanahconnect.member.MemberDtos.CreateMemberRequest;
import com.amanahconnect.member.MemberDtos.DeactivateRequest;
import com.amanahconnect.member.MemberDtos.MemberCounts;
import com.amanahconnect.member.MemberDtos.MemberFilter;
import com.amanahconnect.member.MemberDtos.MemberView;
import com.amanahconnect.member.MemberDtos.UpdateMemberRequest;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/community/members")
@Tag(name = "Community · Members", description = "The community's members (COMMUNITY_ADMIN). Members have no login.")
public class MemberController {

    private static final SortWhitelist SORT =
            SortWhitelist.of(
                    Sort.by(Sort.Direction.ASC, "fullName"),
                    Map.of("name", "fullName", "memberNo", "memberNo", "status", "status", "group", "groupLabel", "joinedOn", "joinedOn", "createdAt", "createdAt"));

    private final MemberService service;

    public MemberController(MemberService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List members", description = "Search (q) across name, member number, email and phone; filter by status and group; sort and paginate. Deleted members are not listed.")
    public PageResponse<MemberView> list(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) @Size(max = 100) String group,
            @Valid PageQuery page) {
        return service.list(communityId, new MemberFilter(q, status, group), SORT.toPageRequest(page));
    }

    @GetMapping("/counts")
    @Operation(summary = "Dashboard counts", description = "Total, active, inactive, new this month and registrations waiting for review.")
    public MemberCounts counts(@CurrentCommunity UUID communityId) {
        return service.counts(communityId);
    }

    @GetMapping("/export")
    @Operation(summary = "Export members as CSV", description = "Streams the members matching the same filters as the list. Audited.")
    public void export(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) @Size(max = 100) String group,
            HttpServletResponse response)
            throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"members-" + LocalDate.now() + ".csv\"");
        response.setHeader("Cache-Control", "no-store");
        Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        service.exportCsv(communityId, new MemberFilter(q, status, group), out);
        out.flush();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Member detail")
    public MemberView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(communityId, id);
    }

    @PostMapping
    @AuditHandledBy("MemberService records MEMBER_CREATED")
    @Operation(summary = "Create a member", description = "The member number is generated from the community's prefix. 402 when the plan's member limit is reached; 409 DUPLICATE_EMAIL unless allowDuplicateEmail is true. Sends the welcome email when it is switched on and the member has an address and consent.")
    public ResponseEntity<MemberView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody CreateMemberRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(communityId, body));
    }

    @PatchMapping("/{id}")
    @AuditHandledBy("MemberService records MEMBER_UPDATED with before and after")
    @Operation(summary = "Update a member", description = "Partial update.")
    public MemberView update(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody UpdateMemberRequest body) {
        return service.update(communityId, id, body);
    }

    @PostMapping("/{id}/deactivate")
    @AuditHandledBy("MemberService records MEMBER_DEACTIVATED")
    @Operation(summary = "Deactivate a member", description = "A reason is required.")
    public MemberView deactivate(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody DeactivateRequest body) {
        return service.deactivate(communityId, id, body);
    }

    @PostMapping("/{id}/activate")
    @AuditHandledBy("MemberService records MEMBER_ACTIVATED")
    @Operation(summary = "Reactivate a member")
    public MemberView activate(@CurrentCommunity UUID communityId, @PathVariable UUID id, @RequestBody(required = false) @Valid ActivateRequest body) {
        return service.activate(communityId, id, body);
    }

    @DeleteMapping("/{id}")
    @AuditHandledBy("MemberService records MEMBER_DELETED")
    @Operation(summary = "Delete a member (soft)", description = "The member is hidden, never removed. 409 MEMBER_HAS_UNPAID_INVOICES while unpaid invoices exist, unless force=true with a reason; a forced deletion is audited with what was outstanding.")
    public ResponseEntity<Void> delete(
            @CurrentCommunity UUID communityId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) @Size(max = 500) String reason) {
        service.delete(communityId, id, force, reason);
        return ResponseEntity.noContent().build();
    }
}
