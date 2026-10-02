package com.amanahconnect.auth.web;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.auth.TwoFactorService;
import com.amanahconnect.auth.web.AuthDtos.RecoveryCodesResponse;
import com.amanahconnect.auth.web.AuthDtos.TwoFactorDisableRequest;
import com.amanahconnect.auth.web.AuthDtos.TwoFactorEnableRequest;
import com.amanahconnect.auth.web.AuthDtos.TwoFactorSetupResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/2fa")
public class TwoFactorController {

    private final TwoFactorService twoFactor;

    public TwoFactorController(TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
    }

    @AuditHandledBy("TwoFactorService records TWO_FACTOR_SETUP_STARTED")
    @PostMapping("/setup")
    public TwoFactorSetupResponse setup(@AuthenticationPrincipal Jwt jwt) {
        TwoFactorService.Setup setup = twoFactor.setup(UUID.fromString(jwt.getSubject()));
        return new TwoFactorSetupResponse(setup.secret(), setup.otpAuthUri());
    }

    /**
     * Turns 2FA on and returns the recovery codes. They are shown exactly once. Afterwards call
     * /auth/refresh to receive an access token without the mfa_setup_required flag.
     */
    @AuditHandledBy("TwoFactorService records TWO_FACTOR_ENABLED")
    @PostMapping("/enable")
    public RecoveryCodesResponse enable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TwoFactorEnableRequest body) {
        return new RecoveryCodesResponse(twoFactor.enable(UUID.fromString(jwt.getSubject()), body.code()));
    }

    @AuditHandledBy("TwoFactorService records TWO_FACTOR_DISABLED")
    @PostMapping("/disable")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TwoFactorDisableRequest body) {
        twoFactor.disable(UUID.fromString(jwt.getSubject()), body.password(), body.code(), body.recoveryCode());
        return ResponseEntity.noContent().build();
    }
}
