package com.amanahconnect.community.admin;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.community.admin.AdminInviteService.InviteRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/admin/invites")
@Tag(name = "Admin · Invitations", description = "Administrator invitations: who is pending, accepted, expired or revoked (SUPER_ADMIN, 2FA completed).")
public class AdminInviteController {

    private static final SortWhitelist SORT =
            SortWhitelist.of(
                    Sort.by(Sort.Direction.DESC, "invitedAt"),
                    Map.of("invitedAt", "invitedAt", "expiresAt", "expiresAt", "email", "email", "communityName", "communityName", "status", "status"));

    private final AdminInviteService service;

    public AdminInviteController(AdminInviteService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List invitations", description = "One row per invited administrator, from their latest invitation. Filter by status (PENDING, ACCEPTED, EXPIRED, REVOKED) and search (q) email, name and community.")
    public PageResponse<InviteRow> list(
            @RequestParam(required = false) @Pattern(regexp = "PENDING|ACCEPTED|EXPIRED|REVOKED") String status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @Valid PageQuery page) {
        return service.list(status, q, SORT.toPageRequest(page));
    }

    @PostMapping("/{userId}/resend")
    @AuditHandledBy("InvitationService records INVITATION_CREATED")
    @Operation(summary = "Send the invitation again", description = "Issues a new link and cancels earlier ones. 409 when the person already accepted or the account is disabled.")
    public ResponseEntity<Void> resend(@PathVariable UUID userId) {
        service.resend(userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @PostMapping("/{userId}/revoke")
    @AuditHandledBy("AdminInviteService records INVITATION_REVOKED")
    @Operation(summary = "Revoke an invitation", description = "The link stops working. The account stays invited and can be sent a new invitation. 409 when nothing is active to revoke.")
    public ResponseEntity<Void> revoke(@PathVariable UUID userId) {
        service.revoke(userId);
        return ResponseEntity.noContent().build();
    }
}
