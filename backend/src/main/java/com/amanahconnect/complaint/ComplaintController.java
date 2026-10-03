package com.amanahconnect.complaint;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.Priority;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.complaint.ComplaintDtos.AddCommentRequest;
import com.amanahconnect.complaint.ComplaintDtos.ChangeStatusRequest;
import com.amanahconnect.complaint.ComplaintDtos.CommentView;
import com.amanahconnect.complaint.ComplaintDtos.ComplaintCounts;
import com.amanahconnect.complaint.ComplaintDtos.ComplaintView;
import com.amanahconnect.complaint.ComplaintDtos.CreateComplaintRequest;
import com.amanahconnect.complaint.ComplaintDtos.UpdateComplaintRequest;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import com.amanahconnect.audit.AuditService;

@RestController
@Validated
@RequestMapping("/api/v1/community/complaints")
@Tag(name = "Community · Complaints", description = "Log and work through complaints, with internal notes and member-visible updates (COMMUNITY_ADMIN).")
public class ComplaintController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final SortWhitelist SORT = SortWhitelist.of(Sort.by(Sort.Direction.DESC, "createdAt"), "createdAt", "updatedAt", "status", "subject", "priority");

    private final ComplaintService service;

    public ComplaintController(ComplaintService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List complaints",
            description = "Filters: status (repeatable), priority, category, memberId, assignedTo, unassigned, mine (assigned to me), slaBreached, createdFrom/createdTo (dates, India time) and q "
                    + "(subject, description, member name or number). Sort by createdAt (default, newest first), updatedAt, status, subject or priority. Each item carries ageDays and the SLA flag.")
    public PageResponse<ComplaintView> list(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) List<ComplaintStatus> status,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) @Size(max = 60) String category,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false, defaultValue = "false") boolean unassigned,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false, defaultValue = "false") boolean slaBreached,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) @Size(max = 100) String q,
            @Valid PageQuery page) {
        ComplaintQueries.Filter filter = new ComplaintQueries.Filter(
                status, priority, category, memberId, assignedTo, unassigned, mine ? AuditService.currentActorId() : null,
                createdFrom == null ? null : createdFrom.atStartOfDay(IST).toInstant(),
                createdTo == null ? null : createdTo.plusDays(1).atStartOfDay(IST).toInstant(),
                slaBreached, q);
        return service.list(communityId, filter, SORT.toPageRequest(page));
    }

    @GetMapping("/counts")
    @Operation(summary = "Dashboard counts", description = "Totals by status, active (open plus in progress), SLA-breached, urgent, unassigned and assigned to me, plus active complaints by category and priority.")
    public ComplaintCounts counts(@CurrentCommunity UUID communityId) {
        return service.counts(communityId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Complaint detail")
    public ComplaintView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(communityId, id);
    }

    @PostMapping
    @AuditHandledBy("ComplaintService records COMPLAINT_CREATED")
    @Operation(summary = "Log a complaint", description = "memberId is optional (a complaint can be about the community in general). Priority defaults to MEDIUM. assignedTo must be an admin of this community.")
    public ResponseEntity<ComplaintView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody CreateComplaintRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(communityId, body));
    }

    @PatchMapping("/{id}")
    @AuditHandledBy("ComplaintService records COMPLAINT_UPDATED with before and after")
    @Operation(summary = "Edit a complaint", description = "Partial. Subject, description, priority, category, member, and the assignee (assignedTo, or unassign=true). A closed complaint cannot be edited until it is reopened.")
    public ComplaintView update(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody UpdateComplaintRequest body) {
        return service.update(communityId, id, body);
    }

    @PostMapping("/{id}/status")
    @AuditHandledBy("ComplaintService records COMPLAINT_STATUS_CHANGED with before and after")
    @Operation(summary = "Change the status",
            description = "OPEN, IN_PROGRESS, RESOLVED or CLOSED. RESOLVED stamps resolvedAt; going back to OPEN or IN_PROGRESS clears resolvedAt and closedAt. An optional note is kept as a comment: member-visible when notifyMember is true "
                    + "(and then emailed, if the member has an address and agreed to email; emailOutcome says what happened), otherwise internal.")
    public ComplaintView changeStatus(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest body) {
        return service.changeStatus(communityId, id, body);
    }

    @GetMapping("/{id}/comments")
    @Operation(summary = "The comment thread", description = "Oldest first. visibility is INTERNAL (admins only) or MEMBER (a visible update).")
    public List<CommentView> comments(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.comments(communityId, id);
    }

    @PostMapping("/{id}/comments")
    @AuditHandledBy("ComplaintService records COMPLAINT_COMMENT_ADDED")
    @Operation(summary = "Add a comment", description = "visibility is required: INTERNAL or MEMBER. notifyMember (MEMBER comments only) emails the member. Not allowed on a closed complaint.")
    public ResponseEntity<CommentView> addComment(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody AddCommentRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addComment(communityId, id, body));
    }
}
