package com.amanahconnect.auth.web;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.auth.PasswordService;
import com.amanahconnect.auth.web.AuthDtos.AcceptInviteRequest;
import com.amanahconnect.auth.web.AuthDtos.AcceptInviteResponse;
import com.amanahconnect.auth.web.AuthDtos.ChangePasswordRequest;
import com.amanahconnect.auth.web.AuthDtos.ForgotPasswordRequest;
import com.amanahconnect.auth.web.AuthDtos.ResetPasswordRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordController {

    private final PasswordService passwords;
    private final RefreshCookie cookie;

    public PasswordController(PasswordService passwords, RefreshCookie cookie) {
        this.passwords = passwords;
        this.cookie = cookie;
    }

    /** Always 202 with an empty body, whether or not the address has an account. */
    @AuditHandledBy("PasswordService records PASSWORD_RESET_REQUESTED")
    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgot(@Valid @RequestBody ForgotPasswordRequest body) {
        passwords.requestReset(body.email());
        return ResponseEntity.accepted().build();
    }

    @AuditHandledBy("PasswordService records PASSWORD_RESET_COMPLETED")
    @PostMapping("/password/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest body) {
        passwords.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear()).build();
    }

    /** Signs the user out everywhere, including this session: sign in again with the new password. */
    @AuditHandledBy("PasswordService records PASSWORD_CHANGED")
    @PostMapping("/password/change")
    public ResponseEntity<Void> change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest body) {
        passwords.changePassword(UUID.fromString(jwt.getSubject()), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear()).build();
    }

    @AuditHandledBy("PasswordService records INVITATION_ACCEPTED")
    @PostMapping("/accept-invite")
    public AcceptInviteResponse acceptInvite(@Valid @RequestBody AcceptInviteRequest body) {
        return new AcceptInviteResponse(passwords.acceptInvitation(body.token(), body.newPassword()));
    }
}
