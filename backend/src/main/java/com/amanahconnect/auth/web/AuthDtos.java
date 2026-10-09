package com.amanahconnect.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of the auth API. Every record that carries a password, token or code
 * overrides {@code toString} so it cannot leak into a log line by accident.
 */
public final class AuthDtos {

    private AuthDtos() {}

    // ---- requests ---------------------------------------------------------------------------

    @io.swagger.v3.oas.annotations.media.Schema(example = "{\"email\":\"admin@lotus-residents.example\",\"password\":\"correct horse battery staple\"}")
    public record LoginRequest(
            @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "LoginRequest[***]";
        }
    }

    public record MfaLoginRequest(
            @NotBlank @Size(max = 4096) String mfaToken,
            @Size(max = 16) String code,
            @Size(max = 32) String recoveryCode) {
        @Override
        public String toString() {
            return "MfaLoginRequest[***]";
        }
    }

    public record ForgotPasswordRequest(@NotBlank @Size(max = 254) String email) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 200) String newPassword) {
        @Override
        public String toString() {
            return "ResetPasswordRequest[***]";
        }
    }

    public record AcceptInviteRequest(
            @NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 200) String newPassword) {
        @Override
        public String toString() {
            return "AcceptInviteRequest[***]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 200) String currentPassword, @NotBlank @Size(max = 200) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    public record TwoFactorEnableRequest(@NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
        @Override
        public String toString() {
            return "TwoFactorEnableRequest[***]";
        }
    }

    public record TwoFactorDisableRequest(
            @NotBlank @Size(max = 200) String password,
            @Size(max = 16) String code,
            @Size(max = 32) String recoveryCode) {
        @Override
        public String toString() {
            return "TwoFactorDisableRequest[***]";
        }
    }

    // ---- responses --------------------------------------------------------------------------

    /** The refresh token is deliberately absent: it travels only in the HttpOnly cookie. */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, boolean mfaSetupRequired) {
        public static TokenResponse bearer(String accessToken, long expiresIn, boolean mfaSetupRequired) {
            return new TokenResponse(accessToken, "Bearer", expiresIn, mfaSetupRequired);
        }
    }

    public record MfaChallengeResponse(boolean mfaRequired, String mfaToken, long expiresIn) {}

    public record AcceptInviteResponse(boolean mfaSetupRequired) {}

    public record TwoFactorSetupResponse(String secret, String otpauthUri) {}

    public record RecoveryCodesResponse(List<String> recoveryCodes) {}

    public record UserSummary(UUID id, String email, String fullName, String role, String status) {}

    public record CommunitySummary(UUID id, String name, String slug, String status) {}

    public record Flags(boolean totpEnabled, boolean mfaSetupRequired, boolean mfaMandatory) {}

    public record MeResponse(UserSummary user, CommunitySummary community, Flags flags) {}
}
