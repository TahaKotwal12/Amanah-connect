package com.amanahconnect.member;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.member.InviteDtos.CreateInviteRequest;
import com.amanahconnect.member.InviteDtos.CreatedInviteView;
import com.amanahconnect.member.InviteDtos.EmailedInviteView;
import com.amanahconnect.member.InviteDtos.InviteByEmailRequest;
import com.amanahconnect.member.InviteDtos.InviteView;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/community/member-invites")
@Tag(name = "Community · Invites", description = "Invite links and self-registration review (COMMUNITY_ADMIN).")
public class InviteController {

    private static final SortWhitelist SORT =
            SortWhitelist.of(Sort.by(Sort.Direction.DESC, "createdAt"), Map.of("createdAt", "createdAt", "expiresAt", "expiresAt"));

    private final InviteService service;

    public InviteController(InviteService service) {
        this.service = service;
    }

    @PostMapping
    @AuditHandledBy("InviteService records MEMBER_INVITE_CREATED")
    @Operation(summary = "Create an invite link", description = "Returns the link and a QR code (PNG, base64) once: only a hash of the token is stored, so they cannot be shown again. Defaults: valid 14 days, 50 registrations.")
    public ResponseEntity<CreatedInviteView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody(required = false) CreateInviteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(communityId, body == null ? new CreateInviteRequest(null, null, null) : body));
    }

    @PostMapping("/email")
    @AuditHandledBy("InviteService records MEMBER_INVITE_EMAILED")
    @Operation(summary = "Invite someone by email", description = "Creates a single-use link and sends it to the address. Counts towards the plan's email quota.")
    public ResponseEntity<EmailedInviteView> inviteByEmail(@CurrentCommunity UUID communityId, @Valid @RequestBody InviteByEmailRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.inviteByEmail(communityId, body));
    }

    @GetMapping
    @Operation(summary = "List invite links", description = "Filter by state: ACTIVE, EXPIRED, USED_UP, REVOKED (default: all).")
    public PageResponse<InviteView> list(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) @Pattern(regexp = "ALL|ACTIVE|EXPIRED|USED_UP|REVOKED") String state,
            @Valid PageQuery page) {
        return service.list(communityId, state, SORT.toPageRequest(page));
    }

    @PostMapping("/{id}/revoke")
    @AuditHandledBy("InviteService records MEMBER_INVITE_REVOKED")
    @Operation(summary = "Revoke an invite link", description = "The link stops working at once.")
    public InviteView revoke(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.revoke(communityId, id);
    }
}
