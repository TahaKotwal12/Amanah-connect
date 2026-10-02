package com.amanahconnect.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings ({@code app.auth.*}). Secrets come from the environment only.
 *
 * @param jwtSecret HS256 signing secret, at least 32 bytes ({@code JWT_SECRET})
 * @param totpEncKey base64 of a 32-byte AES key that encrypts TOTP secrets at rest ({@code TOTP_ENC_KEY})
 * @param frontendBaseUrl base of the links in reset and invitation emails
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        String jwtSecret,
        String totpEncKey,
        @DefaultValue("http://localhost:5173") String frontendBaseUrl,
        @DefaultValue("amanah-connect") String issuer,
        @DefaultValue("PT15M") Duration accessTtl,
        @DefaultValue("PT5M") Duration mfaTtl,
        @DefaultValue("P7D") Duration refreshTtl,
        @DefaultValue("PT30M") Duration resetTtl,
        @DefaultValue("PT48H") Duration inviteTtl,
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("PT15M") Duration lockDuration) {

    public AuthProperties {
        if (bcryptStrength < 4 || bcryptStrength > 16) {
            throw new IllegalArgumentException("app.auth.bcrypt-strength must be between 4 and 16");
        }
    }
}
