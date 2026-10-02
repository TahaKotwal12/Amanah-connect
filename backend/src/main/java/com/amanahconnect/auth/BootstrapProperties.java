package com.amanahconnect.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** First-start super admin ({@code BOOTSTRAP_SUPERADMIN_EMAIL} / {@code BOOTSTRAP_SUPERADMIN_PASSWORD}). */
@ConfigurationProperties(prefix = "app.bootstrap")
public record BootstrapProperties(String superAdminEmail, String superAdminPassword) {

    public boolean configured() {
        return superAdminEmail != null
                && !superAdminEmail.isBlank()
                && superAdminPassword != null
                && !superAdminPassword.isBlank();
    }
}
