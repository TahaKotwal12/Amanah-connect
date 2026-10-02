package com.amanahconnect.auth;

import com.amanahconnect.audit.AuditAction;
import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.Masking;
import com.amanahconnect.common.error.ApiException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first SUPER_ADMIN on startup, and only when all of these hold: no SUPER_ADMIN exists,
 * and both {@code BOOTSTRAP_SUPERADMIN_EMAIL} and {@code BOOTSTRAP_SUPERADMIN_PASSWORD} are set.
 *
 * <p>The account is created with {@code must_setup_2fa}, so its first login can do nothing but enrol
 * in 2FA. The password is checked against the normal password policy and is never logged.
 *
 * <p><b>Remove both variables from the environment after the first start.</b> They are ignored once
 * a super admin exists, but a password sitting in an env file is still a password sitting in a file.
 */
@Component
public class SuperAdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final BootstrapProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    public SuperAdminBootstrap(
            BootstrapProperties properties,
            UserRepository users,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) {
        runOnce();
    }

    /** @return true if an account was created */
    public boolean runOnce() {
        if (!properties.configured()) {
            return false;
        }
        Boolean created = transaction.execute(status -> createIfMissing());
        return Boolean.TRUE.equals(created);
    }

    private boolean createIfMissing() {
        if (users.existsByRole(UserRole.SUPER_ADMIN)) {
            log.warn(
                    "A SUPER_ADMIN already exists, so the bootstrap settings were ignored. "
                            + "Remove BOOTSTRAP_SUPERADMIN_EMAIL and BOOTSTRAP_SUPERADMIN_PASSWORD from the environment.");
            return false;
        }
        String email = properties.superAdminEmail().trim();
        if (!email.contains("@")) {
            throw new IllegalStateException("BOOTSTRAP_SUPERADMIN_EMAIL is not a valid email address");
        }
        try {
            passwordPolicy.validate(properties.superAdminPassword(), email);
        } catch (ApiException e) {
            // Details are rule descriptions only; the password itself is never included.
            throw new IllegalStateException(
                    "BOOTSTRAP_SUPERADMIN_PASSWORD does not meet the password policy: " + String.join(" ", e.details()));
        }

        User admin = new User();
        admin.setEmail(email);
        admin.setFullName("Platform Administrator");
        admin.setRole(UserRole.SUPER_ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setPasswordHash(passwordEncoder.encode(properties.superAdminPassword()));
        admin.setMustSetup2fa(true);
        users.save(admin);
        audit.record(
                AuditAction.SUPER_ADMIN_BOOTSTRAPPED,
                null,
                null,
                "User",
                admin.getId(),
                null,
                Map.of("email", Masking.email(email)));
        log.warn(
                "Created the first SUPER_ADMIN account ({}). It must enrol in 2FA at first login. "
                        + "Remove BOOTSTRAP_SUPERADMIN_EMAIL and BOOTSTRAP_SUPERADMIN_PASSWORD from the environment now.",
                Masking.email(email));
        return true;
    }
}
