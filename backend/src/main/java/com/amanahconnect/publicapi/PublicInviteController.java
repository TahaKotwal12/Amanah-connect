package com.amanahconnect.publicapi;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.member.InviteDtos.PublicInviteView;
import com.amanahconnect.member.InviteDtos.PublicRegisterRequest;
import com.amanahconnect.member.InviteDtos.PublicRegisterResponse;
import com.amanahconnect.member.PublicInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/invites")
@Tag(name = "Public · Invites", description = "What a person with an invite link sees and submits. No login; rate limited per IP.")
public class PublicInviteController {

    private final PublicInviteService service;

    public PublicInviteController(PublicInviteService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    @Operation(summary = "Show the registration form for an invite link", description = "Community name, logo and form fields. Any problem with the link (unknown, expired, revoked, used up) gives the same 404 INVITE_UNAVAILABLE.")
    public ResponseEntity<PublicInviteView> view(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view(token));
    }

    @PostMapping("/{token}/register")
    @AuditHandledBy("PublicInviteService records MEMBER_REGISTRATION_RECEIVED (nothing is stored when the honeypot is filled)")
    @Operation(summary = "Register through an invite link", description = "Creates a pending registration for the community admin to review. Answers 202 whether or not the address is already known, and when the honeypot field `website` is filled (then nothing is stored).")
    public ResponseEntity<PublicRegisterResponse> register(@PathVariable String token, @Valid @RequestBody PublicRegisterRequest body) {
        service.register(token, body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new PublicRegisterResponse(true));
    }
}
