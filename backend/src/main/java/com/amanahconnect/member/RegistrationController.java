package com.amanahconnect.member;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.member.InviteDtos.ApproveRegistrationRequest;
import com.amanahconnect.member.InviteDtos.RegistrationView;
import com.amanahconnect.member.InviteDtos.RejectRegistrationRequest;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/registrations")
@Tag(name = "Community · Invites", description = "Invite links and self-registration review (COMMUNITY_ADMIN).")
public class RegistrationController {

    private static final SortWhitelist SORT =
            SortWhitelist.of(Sort.by(Sort.Direction.DESC, "createdAt"), Map.of("createdAt", "createdAt", "name", "fullName"));

    private final RegistrationService service;

    public RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List registrations", description = "People who registered through an invite link. Filter by status (default: all).")
    public PageResponse<RegistrationView> list(@CurrentCommunity UUID communityId, @RequestParam(required = false) RegistrationStatus status, @Valid PageQuery page) {
        return service.list(communityId, status, SORT.toPageRequest(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Registration detail")
    public RegistrationView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(communityId, id);
    }

    @PostMapping("/{id}/approve")
    @AuditHandledBy("RegistrationService records MEMBER_REGISTRATION_APPROVED (and MemberService MEMBER_CREATED)")
    @Operation(summary = "Approve a registration", description = "Creates the member and sends the welcome email. 402 when the plan's member limit is reached; 409 DUPLICATE_EMAIL unless allowDuplicateEmail is true; 409 if already decided.")
    public RegistrationView approve(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRegistrationRequest body) {
        return service.approve(communityId, id, body);
    }

    @PostMapping("/{id}/reject")
    @AuditHandledBy("RegistrationService records MEMBER_REGISTRATION_REJECTED")
    @Operation(summary = "Reject a registration", description = "A reason is required.")
    public RegistrationView reject(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody RejectRegistrationRequest body) {
        return service.reject(communityId, id, body);
    }
}
