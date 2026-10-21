package com.amanahconnect.devseed;

import com.amanahconnect.auth.PasswordPolicy;
import com.amanahconnect.auth.RecoveryCode;
import com.amanahconnect.auth.RecoveryCodeRepository;
import com.amanahconnect.auth.RecoveryCodes;
import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.UserRole;
import com.amanahconnect.auth.UserStatus;
import com.amanahconnect.auth.crypto.TotpSecretCipher;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityRole;
import com.amanahconnect.community.CommunityStatus;
import com.amanahconnect.community.CommunityUser;
import com.amanahconnect.community.CommunityUserRepository;
import com.amanahconnect.plan.PlanRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Development-only test accounts: one for each way a sign-in can behave, so the login screens can be
 * tried for real. It is OFF unless {@code app.dev-seed.enabled=true} (env {@code DEV_SEED_ENABLED}),
 * only exists under the {@code local} profile (never {@code prod}), and refuses to run without a
 * password from {@code DEV_SEED_PASSWORD}: no password is stored in the code.
 *
 * <p>Idempotent: every start brings each account back to its described state (password re-applied,
 * lockout reset, forced 2FA un-enrolled again), so a run is also a reset.
 *
 * <p>All accounts use the address domain {@value #DOMAIN}; see docs/dev-seed.md for the table, and
 * how to switch them all off.
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
public class DevSeedData implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedData.class);

    public static final String DOMAIN = "@dev-seed.example.test";

    /**
     * Fixed TOTP secret shared by every seeded 2FA account, so tests can compute valid codes. It is
     * public on purpose: these accounts exist only in development.
     */
    public static final String TOTP_SECRET = "DEVSEEDDEVSEEDDEVSEEDDEVSEED2222";

    /** A recovery code every seeded 2FA account has. Single use: a restart makes it usable again. */
    public static final String RECOVERY_CODE = "DEVSEED-RECOV1";

    private static final String ACTIVE_COMMUNITY = "dev-seed-active";
    private static final String TWO_FACTOR_COMMUNITY = "dev-seed-2fa-required";
    private static final String SUSPENDED_COMMUNITY = "dev-seed-suspended";

    /** One account and the state it should be in. */
    private record Account(
            String local,
            String fullName,
            UserRole role,
            UserStatus status,
            String communitySlug,
            CommunityRole communityRole,
            boolean totp,
            boolean mustSetup2fa,
            boolean locked,
            boolean hasPassword) {}

    private static final List<Account> ACCOUNTS = List.of(
            // Platform staff
            new Account("superadmin.setup", "Dev Super Admin (must set up 2FA)", UserRole.SUPER_ADMIN, UserStatus.ACTIVE, null, null, false, true, false, true),
            new Account("superadmin.2fa", "Dev Super Admin (2FA on)", UserRole.SUPER_ADMIN, UserStatus.ACTIVE, null, null, true, false, false, true),
            // Community admins
            new Account("owner", "Dev Community Owner", UserRole.COMMUNITY_ADMIN, UserStatus.ACTIVE, ACTIVE_COMMUNITY, CommunityRole.OWNER, false, false, false, true),
            new Account("admin.2fa", "Dev Community Admin (2FA on)", UserRole.COMMUNITY_ADMIN, UserStatus.ACTIVE, ACTIVE_COMMUNITY, CommunityRole.ADMIN, true, false, false, true),
            new Account("owner.must2fa", "Dev Owner (community requires 2FA)", UserRole.COMMUNITY_ADMIN, UserStatus.ACTIVE, TWO_FACTOR_COMMUNITY, CommunityRole.OWNER, false, false, false, true),
            new Account("owner.suspended", "Dev Owner (community suspended)", UserRole.COMMUNITY_ADMIN, UserStatus.ACTIVE, SUSPENDED_COMMUNITY, CommunityRole.OWNER, false, false, false, true),
            // Accounts that must NOT get in
            new Account("locked", "Dev Admin (locked out)", UserRole.COMMUNITY_ADMIN, UserStatus.ACTIVE, ACTIVE_COMMUNITY, CommunityRole.ADMIN, false, false, true, true),
            new Account("disabled", "Dev Admin (disabled)", UserRole.COMMUNITY_ADMIN, UserStatus.DISABLED, ACTIVE_COMMUNITY, CommunityRole.ADMIN, false, false, false, true),
            new Account("invited", "Dev Admin (invited, no password yet)", UserRole.COMMUNITY_ADMIN, UserStatus.INVITED, ACTIVE_COMMUNITY, CommunityRole.ADMIN, false, false, false, false));

    private final UserRepository users;
    private final CommunityRepository communities;
    private final CommunityUserRepository communityUsers;
    private final RecoveryCodeRepository recoveryCodes;
    private final PlanRepository plans;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TotpSecretCipher cipher;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String password;

    public DevSeedData(
            UserRepository users,
            CommunityRepository communities,
            CommunityUserRepository communityUsers,
            RecoveryCodeRepository recoveryCodes,
            PlanRepository plans,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            TotpSecretCipher cipher,
            JdbcTemplate jdbc,
            EntityManager em,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${app.dev-seed.password:}") String password) {
        this.users = users;
        this.communities = communities;
        this.communityUsers = communityUsers;
        this.recoveryCodes = recoveryCodes;
        this.plans = plans;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.em = em;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (password == null || password.isBlank()) {
            log.error("Dev seed is enabled but DEV_SEED_PASSWORD is not set, so no test accounts were created.");
            return;
        }
        try {
            passwordPolicy.validate(password, null);
        } catch (ApiException e) {
            // The messages are rule descriptions only; the password itself is never logged.
            log.error("DEV_SEED_PASSWORD does not meet the password policy: {}", String.join(" ", e.details()));
            return;
        }
        transaction.executeWithoutResult(status -> seed());
    }

    private void seed() {
        Community active = ensureCommunity(ACTIVE_COMMUNITY, "Dev Seed Community", CommunityStatus.ACTIVE);
        Community twoFactor = ensureCommunity(TWO_FACTOR_COMMUNITY, "Dev Seed Community (requires 2FA)", CommunityStatus.ACTIVE);
        Community suspended = ensureCommunity(SUSPENDED_COMMUNITY, "Dev Seed Community (suspended)", CommunityStatus.SUSPENDED);

        UUID firstSuperAdmin = null;
        for (Account account : ACCOUNTS) {
            User user = ensureUser(account);
            if (account.role() == UserRole.SUPER_ADMIN && firstSuperAdmin == null) {
                firstSuperAdmin = user.getId();
            }
            if (account.communitySlug() != null) {
                Community community = switch (account.communitySlug()) {
                    case ACTIVE_COMMUNITY -> active;
                    case TWO_FACTOR_COMMUNITY -> twoFactor;
                    default -> suspended;
                };
                ensureLink(community, user, account.communityRole());
                if (account.communityRole() == CommunityRole.OWNER && community.getOwner() == null) {
                    community.setOwner(user);
                }
            }
        }
        em.flush();

        // Plain SQL after every entity change is flushed, so Hibernate cannot write stale values over it.
        jdbc.update("update communities set settings = settings || '{\"require_2fa\": true}'::jsonb where id = ?", twoFactor.getId());
        for (Community community : List.of(active, twoFactor, suspended)) {
            ensureSubscription(community, firstSuperAdmin);
        }
        log.warn(
                "Dev seed: {} test accounts at {} are ready (development only; see docs/dev-seed.md).",
                ACCOUNTS.size(),
                DOMAIN);
    }

    private Community ensureCommunity(String slug, String name, CommunityStatus status) {
        Community community = communities.findBySlug(slug).orElseGet(() -> {
            Community created = new Community();
            created.setSlug(slug);
            created.setName(name);
            created.setPlan(plans.findByCode("STARTER").orElseThrow());
            created.setCountry("IN");
            created.setStatusChangedAt(clock.instant());
            return created;
        });
        community.setName(name);
        community.setStatus(status);
        community.setStatusReason(status == CommunityStatus.SUSPENDED ? "Dev seed: suspended on purpose." : "Dev seed.");
        // The insert fires the database trigger that gives a community its default categories.
        communities.save(community);
        em.flush();
        return community;
    }

    private User ensureUser(Account account) {
        String email = account.local() + DOMAIN;
        User user = users.findByEmail(email).orElseGet(User::new);
        boolean created = user.getId() == null;
        user.setEmail(email);
        user.setFullName(account.fullName());
        user.setRole(account.role());
        user.setStatus(account.status());
        if (account.hasPassword()) {
            // BCrypt is slow by design, so only hash again when the configured password has changed.
            if (created || user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
                user.setPasswordHash(passwordEncoder.encode(password));
            }
        } else {
            user.setPasswordHash(null);
        }
        user.setMustSetup2fa(account.mustSetup2fa());
        user.setFailedAttempts(account.locked() ? 5 : 0);
        user.setLockedUntil(account.locked() ? Instant.now(clock).plus(1, ChronoUnit.DAYS) : null);
        user.setTotpLastUsedStep(null);
        users.save(user);
        em.flush(); // the id is needed below: the TOTP secret is bound to it

        recoveryCodes.deleteByUserId(user.getId());
        em.flush(); // Hibernate runs inserts before deletes: without this the new row hits the unique key
        if (account.totp()) {
            user.setTotpSecretEnc(cipher.encrypt(TOTP_SECRET, user.getId().toString()));
            user.setTotpEnabled(true);
            RecoveryCode code = new RecoveryCode();
            code.setUser(user);
            code.setCodeHash(RecoveryCodes.hash(RECOVERY_CODE));
            recoveryCodes.save(code);
        } else {
            user.setTotpSecretEnc(null);
            user.setTotpEnabled(false);
        }
        return user;
    }

    private void ensureLink(Community community, User user, CommunityRole role) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from community_users where community_id = ? and user_id = ?",
                Integer.class,
                community.getId(),
                user.getId());
        if (existing != null && existing > 0) {
            jdbc.update("update community_users set role = ? where community_id = ? and user_id = ?", role.name(), community.getId(), user.getId());
            return;
        }
        CommunityUser link = new CommunityUser();
        link.setCommunityId(community.getId());
        link.setUser(user);
        link.setRole(role);
        communityUsers.save(link);
    }

    private void ensureSubscription(Community community, UUID recordedBy) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from platform_subscriptions where community_id = ? and reference = 'dev-seed'",
                Integer.class,
                community.getId());
        if (existing != null && existing > 0) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        jdbc.update(
                "insert into platform_subscriptions (id, community_id, plan_id, period_start, period_end, amount, reference, status, recorded_by, paid_on)"
                        + " values (?, ?, ?, ?, ?, 0, 'dev-seed', 'ACTIVE', ?, ?)",
                UUID.randomUUID(),
                community.getId(),
                community.getPlan().getId(),
                today,
                today.plusYears(1),
                recordedBy,
                today);
    }
}
