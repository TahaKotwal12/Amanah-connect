package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amanahconnect.audit.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

class SuperAdminBootstrapTest {

    private static final String PASSWORD = "Correct-Horse-9-Staple";

    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SuperAdminBootstrap.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private SuperAdminBootstrap bootstrap(String email, String password) {
        return new SuperAdminBootstrap(new BootstrapProperties(email, password), users, encoder, new PasswordPolicy(), audit, mock(PlatformTransactionManager.class));
    }

    @Test
    void doesNothingWhenNotConfigured() {
        assertThat(bootstrap(null, null).runOnce()).isFalse();
        assertThat(bootstrap("", "").runOnce()).isFalse();
        assertThat(bootstrap("admin@example.test", "").runOnce()).as("both variables are required").isFalse();
        assertThat(bootstrap("", PASSWORD).runOnce()).isFalse();

        verifyNoInteractions(users, audit);
    }

    @Test
    void doesNothingWhenASuperAdminAlreadyExists() {
        when(users.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(true);

        assertThat(bootstrap("admin@example.test", PASSWORD).runOnce()).isFalse();

        verify(users, never()).save(any());
        assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("Remove BOOTSTRAP_SUPERADMIN_EMAIL"));
    }

    @Test
    void createsTheSuperAdminWithMandatoryTwoFactorSetup() {
        when(users.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(false);

        assertThat(bootstrap("Admin@Example.Test", PASSWORD).runOnce()).isTrue();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.isMustSetup2fa()).isTrue();
        assertThat(admin.isTotpEnabled()).isFalse();
        assertThat(admin.getEmail()).isEqualTo("Admin@Example.Test");
        assertThat(admin.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
        verify(audit).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void logsThatItRanButNeverThePasswordOrTheFullAddress() {
        bootstrap("admin@example.test", PASSWORD).runOnce();

        String everything = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(everything).contains("Created the first SUPER_ADMIN").contains("Remove BOOTSTRAP_SUPERADMIN_EMAIL and BOOTSTRAP_SUPERADMIN_PASSWORD");
        assertThat(everything).doesNotContain(PASSWORD).doesNotContain("admin@example.test").contains("a***@example.test");
    }

    @Test
    void aWeakBootstrapPasswordAbortsStartupWithoutEchoingIt() {
        assertThatThrownBy(() -> bootstrap("admin@example.test", "weakpass").runOnce())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password policy")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("weakpass"));

        verify(users, never()).save(any());
    }

    @Test
    void anInvalidEmailAbortsStartup() {
        assertThatThrownBy(() -> bootstrap("not-an-email", PASSWORD).runOnce()).isInstanceOf(IllegalStateException.class).hasMessageContaining("BOOTSTRAP_SUPERADMIN_EMAIL");
    }
}
