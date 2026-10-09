package com.amanahconnect.complaint;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.UserStatus;
import com.amanahconnect.common.Priority;
import com.amanahconnect.common.Text;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityUserRepository;
import com.amanahconnect.complaint.ComplaintDtos.AddCommentRequest;
import com.amanahconnect.complaint.ComplaintDtos.ChangeStatusRequest;
import com.amanahconnect.complaint.ComplaintDtos.CommentView;
import com.amanahconnect.complaint.ComplaintDtos.ComplaintCounts;
import com.amanahconnect.complaint.ComplaintDtos.ComplaintView;
import com.amanahconnect.complaint.ComplaintDtos.CreateComplaintRequest;
import com.amanahconnect.complaint.ComplaintDtos.UpdateComplaintRequest;
import com.amanahconnect.complaint.ComplaintDtos.Visibility;
import com.amanahconnect.complaint.ComplaintQueries.Filter;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.notification.Delivery;
import com.amanahconnect.notification.MemberMail;
import com.amanahconnect.tenant.TenantGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Complaints logged by community admins, usually on behalf of a member. A complaint is never deleted: it is closed. Comments are
 * INTERNAL (admins only, never emailed) or MEMBER (a visible update, optionally emailed to the member). The member is only
 * ever emailed when an admin asks for it in that request, and only if they have an address and agreed to email.
 */
@Service
@Transactional
public class ComplaintService {

    static final String UPDATE_TEMPLATE = "complaint-update";

    private final ComplaintRepository complaints;
    private final ComplaintCommentRepository comments;
    private final ComplaintQueries queries;
    private final MemberRepository members;
    private final CommunityRepository communities;
    private final CommunityUserRepository communityUsers;
    private final MemberMail mail;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final Clock clock;
    private final jakarta.persistence.EntityManager em;

    public ComplaintService(
            ComplaintRepository complaints,
            ComplaintCommentRepository comments,
            ComplaintQueries queries,
            MemberRepository members,
            CommunityRepository communities,
            CommunityUserRepository communityUsers,
            MemberMail mail,
            NamedParameterJdbcTemplate jdbc,
            AuditService audit,
            TenantGuard tenantGuard,
            Clock clock,
            jakarta.persistence.EntityManager em) {
        this.complaints = complaints;
        this.comments = comments;
        this.queries = queries;
        this.members = members;
        this.communities = communities;
        this.communityUsers = communityUsers;
        this.mail = mail;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.clock = clock;
        this.em = em;
    }

    // ---- reads ------------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ComplaintView> list(UUID communityId, Filter filter, Pageable page) {
        return queries.search(communityId, filter, page);
    }

    @Transactional(readOnly = true)
    public ComplaintView get(UUID communityId, UUID id) {
        return tenantGuard.found(queries.find(communityId, id));
    }

    @Transactional(readOnly = true)
    public ComplaintCounts counts(UUID communityId) {
        return queries.counts(communityId, AuditService.currentActorId());
    }

    @Transactional(readOnly = true)
    public List<CommentView> comments(UUID communityId, UUID complaintId) {
        tenantGuard.found(complaints.findByIdAndCommunityId(complaintId, communityId));
        return jdbc.query(
                "SELECT cc.id, cc.author_user_id, u.full_name, cc.body, cc.internal, cc.created_at FROM complaint_comments cc JOIN users u ON u.id = cc.author_user_id"
                        + " WHERE cc.community_id = :c AND cc.complaint_id = :id ORDER BY cc.created_at, cc.id",
                new MapSqlParameterSource("c", communityId).addValue("id", complaintId),
                (rs, n) -> new CommentView(rs.getObject("id", UUID.class), rs.getObject("author_user_id", UUID.class), rs.getString("full_name"), rs.getString("body"),
                        rs.getBoolean("internal") ? Visibility.INTERNAL : Visibility.MEMBER, rs.getTimestamp("created_at").toInstant(), null));
    }

    // ---- writes -----------------------------------------------------------------------------------------------------------

    public ComplaintView create(UUID communityId, CreateComplaintRequest request) {
        tenantGuard.requireWritable();
        Complaint complaint = new Complaint();
        complaint.setCommunityId(communityId);
        if (request.memberId() != null) complaint.setMember(liveMember(communityId, request.memberId()));
        complaint.setSubject(subject(request.subject()));
        complaint.setDescription(request.description().trim());
        complaint.setPriority(request.priority() == null ? Priority.MEDIUM : request.priority());
        complaint.setCategory(category(request.category()));
        if (request.assignedTo() != null) complaint.setAssignedTo(requireAdmin(communityId, request.assignedTo()));
        complaint.setCreatedBy(AuditService.currentActorId());
        complaints.save(complaint);
        em.flush();
        audit.record("COMPLAINT_CREATED", "Complaint", complaint.getId(), null, snapshot(complaint));
        return get(communityId, complaint.getId());
    }

    public ComplaintView update(UUID communityId, UUID id, UpdateComplaintRequest request) {
        tenantGuard.requireWritable();
        Complaint complaint = lock(communityId, id);
        requireOpenForChanges(complaint);
        Map<String, Object> before = snapshot(complaint);
        if (request.subject() != null) complaint.setSubject(subject(request.subject()));
        if (request.description() != null) complaint.setDescription(request.description().trim());
        if (request.priority() != null) complaint.setPriority(request.priority());
        if (request.category() != null) complaint.setCategory(category(request.category()));
        if (request.memberId() != null) complaint.setMember(liveMember(communityId, request.memberId()));
        if (Boolean.TRUE.equals(request.unassign())) {
            if (request.assignedTo() != null) throw invalid("assignedTo", "cannot be combined with unassign");
            complaint.setAssignedTo(null);
        } else if (request.assignedTo() != null) {
            complaint.setAssignedTo(requireAdmin(communityId, request.assignedTo()));
        }
        complaints.save(complaint);
        em.flush();
        audit.record("COMPLAINT_UPDATED", "Complaint", id, before, snapshot(complaint));
        return get(communityId, id);
    }

    public ComplaintView changeStatus(UUID communityId, UUID id, ChangeStatusRequest request) {
        tenantGuard.requireWritable();
        Complaint complaint = lock(communityId, id);
        ComplaintStatus from = complaint.getStatus();
        ComplaintStatus to = request.status();
        boolean notify = Boolean.TRUE.equals(request.notifyMember());
        String note = Text.blankToNull(request.note() == null ? null : request.note().trim());
        if (notify && complaint.getMember() == null) {
            throw invalid("notifyMember", "this complaint has no member to notify");
        }
        if (from == to) {
            return get(communityId, id); // nothing to do; asking for the state it is already in is not an error
        }
        Map<String, Object> before = snapshot(complaint);
        Instant now = clock.instant();
        complaint.setStatus(to);
        switch (to) {
            case RESOLVED -> {
                complaint.setResolvedAt(now);
                complaint.setClosedAt(null);
            }
            case CLOSED -> complaint.setClosedAt(now); // a resolved complaint keeps the time it was resolved
            case OPEN, IN_PROGRESS -> { // reopening
                complaint.setResolvedAt(null);
                complaint.setClosedAt(null);
            }
        }
        complaints.save(complaint);
        if (note != null) addCommentRow(complaint, note, notify ? Visibility.MEMBER : Visibility.INTERNAL);
        em.flush();
        Map<String, Object> after = snapshot(complaint);
        if (note != null) after.put("noteVisibility", notify ? "MEMBER" : "INTERNAL");
        audit.record("COMPLAINT_STATUS_CHANGED", "Complaint", id, before, after);
        String outcome = notify ? emailMember(communityId, complaint, "status", note).name() : null;
        return get(communityId, id).withEmailOutcome(outcome);
    }

    public CommentView addComment(UUID communityId, UUID complaintId, AddCommentRequest request) {
        tenantGuard.requireWritable();
        Complaint complaint = lock(communityId, complaintId);
        if (complaint.getStatus() == ComplaintStatus.CLOSED) {
            throw new ApiException(ErrorCode.COMPLAINT_CLOSED, "This complaint is closed. Reopen it to add to it.");
        }
        boolean notify = Boolean.TRUE.equals(request.notifyMember());
        if (notify && request.visibility() == Visibility.INTERNAL) {
            throw invalid("notifyMember", "an internal note is never emailed; make it a member update instead");
        }
        if (notify && complaint.getMember() == null) {
            throw invalid("notifyMember", "this complaint has no member to notify");
        }
        ComplaintComment comment = addCommentRow(complaint, request.body().trim(), request.visibility());
        em.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("complaintId", complaintId.toString());
        after.put("visibility", request.visibility().name());
        after.put("length", comment.getBody().length());
        audit.record("COMPLAINT_COMMENT_ADDED", "ComplaintComment", comment.getId(), null, after);
        String outcome = notify ? emailMember(communityId, complaint, "comment", comment.getBody()).name() : null;
        return new CommentView(comment.getId(), comment.getAuthorUserId(), authorName(comment.getAuthorUserId()), comment.getBody(), request.visibility(), comment.getCreatedAt(), outcome);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private ComplaintComment addCommentRow(Complaint complaint, String body, Visibility visibility) {
        ComplaintComment comment = new ComplaintComment();
        comment.setCommunityId(complaint.getCommunityId());
        comment.setComplaint(complaint);
        comment.setAuthorUserId(AuditService.currentActorId());
        comment.setBody(body);
        comment.setInternal(visibility == Visibility.INTERNAL);
        return comments.save(comment);
    }

    private Delivery emailMember(UUID communityId, Complaint complaint, String what, String message) {
        Community community = communities.findById(communityId).orElseThrow();
        Member member = complaint.getMember();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("communityName", community.getName());
        payload.put("memberName", member.getFullName());
        payload.put("subject", complaint.getSubject());
        payload.put("status", complaint.getStatus().name());
        payload.put("about", what);
        payload.put("message", message);
        payload.put("contactEmail", community.getContactEmail());
        return mail.send(community, member, UPDATE_TEMPLATE, payload);
    }

    private Complaint lock(UUID communityId, UUID id) {
        return tenantGuard.found(complaints.findWithLockByIdAndCommunityId(id, communityId));
    }

    private static void requireOpenForChanges(Complaint complaint) {
        if (complaint.getStatus() == ComplaintStatus.CLOSED) {
            throw new ApiException(ErrorCode.COMPLAINT_CLOSED, "This complaint is closed. Reopen it to change it.");
        }
    }

    private Member liveMember(UUID communityId, UUID memberId) {
        return tenantGuard.found(members.findByIdAndCommunityIdAndDeletedAtIsNull(memberId, communityId));
    }

    /** The assignee must be an active admin of this community. A stranger and a nobody get the same answer. */
    private UUID requireAdmin(UUID communityId, UUID userId) {
        boolean ok = communityUsers.findByCommunityId(communityId).stream()
                .anyMatch(link -> link.getUser().getId().equals(userId) && link.getUser().getStatus() == UserStatus.ACTIVE);
        if (!ok) throw invalid("assignedTo", "must be an active admin of this community");
        return userId;
    }

    private String authorName(UUID userId) {
        return jdbc.queryForObject("SELECT full_name FROM users WHERE id = :id", new MapSqlParameterSource("id", userId), String.class);
    }

    private static String subject(String value) {
        String cleaned = Text.singleLine(value);
        if (cleaned.isEmpty()) throw invalid("subject", "must not be blank");
        return cleaned;
    }

    private static String category(String value) {
        String cleaned = value == null ? null : Text.blankToNull(Text.singleLine(value));
        return cleaned;
    }

    private static Map<String, Object> snapshot(Complaint c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("subjectLength", c.getSubject().length()); // the text itself can name a person and the audit trail cannot be edited
        map.put("status", c.getStatus().name());
        map.put("priority", c.getPriority().name());
        map.put("category", c.getCategory());
        map.put("memberId", c.getMember() == null ? null : c.getMember().getId().toString());
        map.put("assignedTo", c.getAssignedTo() == null ? null : c.getAssignedTo().toString());
        map.put("resolvedAt", c.getResolvedAt() == null ? null : c.getResolvedAt().toString());
        map.put("closedAt", c.getClosedAt() == null ? null : c.getClosedAt().toString());
        return map;
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}
