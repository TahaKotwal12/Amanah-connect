package com.amanahconnect.auth.crypto;

import com.amanahconnect.auth.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Loads the signing and encryption keys.
 *
 * <p>Outside the {@code local} and {@code test} profiles a missing or weak key aborts startup. In
 * those two profiles a throwaway random key is generated and a warning is logged, so a developer
 * can run without setup (tokens, and any TOTP secret saved by a previous run, stop working after a
 * restart). There is deliberately no built-in default key: a value in source control would be a
 * universal credential for any deployment that forgot to set its own.
 */
@Configuration
public class AuthKeysConfig {

    private static final Logger log = LoggerFactory.getLogger(AuthKeysConfig.class);
    private static final int MIN_JWT_SECRET_BYTES = 32;

    @Bean
    public AuthKeys authKeys(AuthProperties properties, Environment environment) {
        boolean ephemeralAllowed = environment.acceptsProfiles(Profiles.of("local", "test"));
        return new AuthKeys(jwtKey(properties, ephemeralAllowed), totpKey(properties, ephemeralAllowed));
    }

    private static SecretKey jwtKey(AuthProperties properties, boolean ephemeralAllowed) {
        String configured = properties.jwtSecret();
        if (configured == null || configured.isBlank()) {
            if (!ephemeralAllowed) {
                throw new IllegalStateException("JWT_SECRET is required (at least 32 bytes)");
            }
            log.warn("JWT_SECRET is not set: using a random key for this run, existing tokens stop working on restart");
            return new SecretKeySpec(random(48), "HmacSHA256");
        }
        byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_JWT_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static SecretKey totpKey(AuthProperties properties, boolean ephemeralAllowed) {
        String configured = properties.totpEncKey();
        if (configured == null || configured.isBlank()) {
            if (!ephemeralAllowed) {
                throw new IllegalStateException("TOTP_ENC_KEY is required (base64 of 32 random bytes)");
            }
            log.warn("TOTP_ENC_KEY is not set: using a random key for this run, saved 2FA secrets become unreadable on restart");
            return new SecretKeySpec(random(32), "AES");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TOTP_ENC_KEY must be valid base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("TOTP_ENC_KEY must decode to exactly 32 bytes (AES-256)");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
