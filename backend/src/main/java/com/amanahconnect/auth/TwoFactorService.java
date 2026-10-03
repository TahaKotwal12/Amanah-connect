package com.amanahconnect.auth;

import com.amanahconnect.audit.AuditAction;
import com.amanahconnect.auth.crypto.TotpSecretCipher;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** TOTP enrolment, verification and removal, with single-use recovery codes. */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class TwoFactorService {

    public record Setup(String secret, String otpAuthUri) {}

    private final UserRepository users;
    private final RecoveryCodeRepository recoveryCodes;
    private final TotpService totp;
    private final TotpSecretCipher cipher;
    private final PasswordEncoder passwordEncoder;
    private final UserSecurityPolicy policy;
    private final LockoutService lockout;
    private final AuthAudit audit;
    private final AuthEmails emails;
    private final Clock clock;

    public TwoFactorService(
            UserRepository users,
            RecoveryCodeRepository recoveryCodes,
            TotpService totp,
            TotpSecretCipher cipher,
            PasswordEncoder passwordEncoder,
            UserSecurityPolicy policy,
            LockoutService lockout,
            AuthAudit audit,
            AuthEmails emails,
            Clock clock) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.totp = totp;
        this.cipher = cipher;
        this.passwordEncoder = passwordEncoder;
        this.policy = policy;
        this.lockout = lockout;
        this.audit = audit;
        this.emails = emails;
        this.clock = clock;
    }

    /** Generates a pending secret. It only becomes active once {@link #enable} proves a code works. */
    public Setup setup(UUID userId) {
        User user = lock(userId);
        if (user.isTotpEnabled()) {
            throw new ApiException(ErrorCode.MFA_ALREADY_ENABLED, "Two-factor authentication is already enabled.");
        }
        String secret = totp.newSecret();
        user.setTotpSecretEnc(cipher.encrypt(secret, userId.toString()));
        user.setTotpLastUsedStep(null);
        audit.event(AuditAction.TWO_FACTOR_SETUP_STARTED, user, Map.of());
        return new Setup(secret, totp.otpAuthUri(secret, user.getEmail()));
    }

    /** Verifies the first code, turns 2FA on and returns the recovery codes. This is the only time they are shown. */
    public List<String> enable(UUID userId, String code) {
        User user = lock(userId);
        if (user.isTotpEnabled()) {
            throw new ApiException(ErrorCode.MFA_ALREADY_ENABLED, "Two-factor authentication is already enabled.");
        }
        if (user.getTotpSecretEnc() == null) {
            throw new ApiException(ErrorCode.MFA_SETUP_NOT_STARTED, "Start two-factor setup first.");
        }
        OptionalLong step = totp.verify(secretOf(user), code, null);
        if (step.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_MFA_CODE, "The verification code is incorrect.");
        }
        user.setTotpEnabled(true);
        user.setTotpLastUsedStep(step.getAsLong());
        user.setMustSetup2fa(false);

        recoveryCodes.deleteByUserId(userId);
        List<String> plain = RecoveryCodes.generate();
        for (String code1 : plain) {
            RecoveryCode entity = new RecoveryCode();
            entity.setUser(user);
            entity.setCodeHash(RecoveryCodes.hash(code1));
            recoveryCodes.save(entity);
        }
        audit.event(AuditAction.TWO_FACTOR_ENABLED, user, Map.of("recoveryCodes", plain.size()));
        emails.twoFactorChanged(user, "turned on");
        return plain;
    }

    /** Needs the password and a current code (or a recovery code); refused where 2FA is mandatory. */
    public void disable(UUID userId, String password, String code, String recoveryCode) {
        User user = lock(userId);
        if (!user.isTotpEnabled()) {
            throw new ApiException(ErrorCode.MFA_NOT_ENABLED, "Two-factor authentication is not enabled.");
        }
        if (policy.isMfaMandatory(user)) {
            throw new ApiException(
                    ErrorCode.MFA_REQUIRED_BY_POLICY,
                    "Two-factor authentication is required for this account and cannot be turned off.");
        }
        if (lockout.isLocked(user)) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED, "Too many failed attempts. Try again later.");
        }
        lockout.clearIfExpired(user);
        if (password == null || user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            lockout.registerFailure(user, AuditAction.LOGIN_FAILED);
            throw new ApiException(ErrorCode.INVALID_CURRENT_PASSWORD, "The password is incorrect.");
        }
        if (!verifySecondFactor(user, code, recoveryCode)) {
            lockout.registerFailure(user, AuditAction.MFA_FAILED);
            throw new ApiException(ErrorCode.INVALID_MFA_CODE, "The verification code is incorrect.");
        }
        user.setTotpEnabled(false);
        user.setTotpSecretEnc(null);
        user.setTotpLastUsedStep(null);
        recoveryCodes.deleteByUserId(userId);
        audit.event(AuditAction.TWO_FACTOR_DISABLED, user, Map.of());
        emails.twoFactorChanged(user, "turned off");
    }

    /**
     * Checks a TOTP code (refusing replays) or a recovery code (consuming it). Mutates the user and
     * recovery rows, so the caller must hold the user's row lock.
     */
    public boolean verifySecondFactor(User user, String code, String recoveryCode) {
        if (code != null && !code.isBlank()) {
            OptionalLong step = totp.verify(secretOf(user), code.trim(), user.getTotpLastUsedStep());
            if (step.isPresent()) {
                user.setTotpLastUsedStep(step.getAsLong());
                return true;
            }
            return false;
        }
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            return recoveryCodes
                    .findByUserIdAndCodeHashAndUsedAtIsNull(user.getId(), RecoveryCodes.hash(recoveryCode))
                    .map(
                            stored -> {
                                stored.setUsedAt(clock.instant());
                                audit.event(
                                        AuditAction.RECOVERY_CODE_USED,
                                        user,
                                        Map.of("remaining", recoveryCodes.countByUserIdAndUsedAtIsNull(user.getId()) - 1));
                                return true;
                            })
                    .orElse(false);
        }
        return false;
    }

    private String secretOf(User user) {
        return cipher.decrypt(user.getTotpSecretEnc(), user.getId().toString());
    }

    private User lock(UUID userId) {
        return users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required."));
    }
}
