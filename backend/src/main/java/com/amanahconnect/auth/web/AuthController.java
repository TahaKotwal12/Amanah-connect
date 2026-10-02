package com.amanahconnect.auth.web;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.auth.AuthService;
import com.amanahconnect.auth.LoginOutcome;
import com.amanahconnect.auth.MeService;
import com.amanahconnect.auth.web.AuthDtos.LoginRequest;
import com.amanahconnect.auth.web.AuthDtos.MeResponse;
import com.amanahconnect.auth.web.AuthDtos.MfaChallengeResponse;
import com.amanahconnect.auth.web.AuthDtos.MfaLoginRequest;
import com.amanahconnect.auth.web.AuthDtos.TokenResponse;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.web.RequestInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final MeService me;
    private final RefreshCookie cookie;

    public AuthController(AuthService auth, MeService me, RefreshCookie cookie) {
        this.auth = auth;
        this.me = me;
        this.cookie = cookie;
    }

    @AuditHandledBy("AuthService records LOGIN_SUCCESS and LOGIN_FAILED")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return respond(auth.login(body.email(), body.password(), RequestInfo.of(request)));
    }

    @AuditHandledBy("AuthService records LOGIN_SUCCESS, MFA_FAILED and RECOVERY_CODE_USED")
    @PostMapping("/login/2fa")
    public ResponseEntity<?> loginWithSecondFactor(@Valid @RequestBody MfaLoginRequest body, HttpServletRequest request) {
        return respond(
                auth.loginWithSecondFactor(
                        body.mfaToken(), body.code(), body.recoveryCode(), RequestInfo.of(request)));
    }

    /** Guarded by {@link CookieEndpointGuardFilter}: needs X-Requested-With and an allowed Origin. */
    @AuditHandledBy("reuse of a rotated token is audited (TOKEN_REUSE_DETECTED); routine rotation is deliberately not")
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken,
            HttpServletRequest request) {
        try {
            return respond(auth.refresh(refreshToken, RequestInfo.of(request)));
        } catch (ApiException e) {
            if (e.code() == ErrorCode.INVALID_REFRESH_TOKEN) {
                // Tell the browser to drop the dead cookie as well as rejecting the request.
                return ResponseEntity.status(e.code().status())
                        .header(HttpHeaders.SET_COOKIE, cookie.clear())
                        .body(com.amanahconnect.common.error.Problems.of(e.code(), e.getMessage()));
            }
            throw e;
        }
    }

    /** Guarded by {@link CookieEndpointGuardFilter}. Always 204: it never reveals whether the token was valid. */
    @AuditHandledBy("AuthService records LOGOUT")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookie.NAME, required = false) String refreshToken) {
        auth.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear()).build();
    }

    @AuditHandledBy("AuthService records LOGOUT_ALL")
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
        auth.logoutAll(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.clear()).build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return me.describe(UUID.fromString(jwt.getSubject()));
    }

    private ResponseEntity<?> respond(LoginOutcome outcome) {
        return switch (outcome) {
            case LoginOutcome.Authenticated done ->
                    ResponseEntity.ok()
                            .header(HttpHeaders.SET_COOKIE, cookie.issue(done.refreshToken()))
                            .body(TokenResponse.bearer(done.accessToken(), done.expiresInSeconds(), done.mfaSetupRequired()));
            case LoginOutcome.MfaChallenge challenge ->
                    ResponseEntity.ok(new MfaChallengeResponse(true, challenge.mfaToken(), challenge.expiresInSeconds()));
        };
    }
}
