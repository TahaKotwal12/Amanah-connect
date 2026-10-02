package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.auth.crypto.AuthKeys;
import com.amanahconnect.auth.crypto.AuthKeysConfig;
import com.amanahconnect.common.Masking;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

class SmallAuthUnitsTest {

    // ---- Tokens -----------------------------------------------------------------------------

    @Test
    void opaqueTokensAre256BitsAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = Tokens.newOpaqueToken();
            assertThat(token).matches("[A-Za-z0-9_-]{43}"); // 32 bytes, base64url, no padding
            seen.add(token);
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    void hashingIsStableHexSha256() {
        assertThat(Tokens.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Tokens.constantTimeEquals("a", "a")).isTrue();
        assertThat(Tokens.constantTimeEquals("a", "b")).isFalse();
    }

    // ---- recovery codes ---------------------------------------------------------------------

    @Test
    void recoveryCodesAreTenDistinctUnambiguousCodes() {
        List<String> codes = RecoveryCodes.generate();

        assertThat(codes).hasSize(10).doesNotHaveDuplicates().allMatch(c -> c.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}"));
        assertThat(RecoveryCodes.generate()).as("each generation is fresh").doesNotContainAnyElementsOf(codes);
    }

    @Test
    void recoveryCodeInputIsNormalisedBeforeHashing() {
        assertThat(RecoveryCodes.hash("abcdef-ghjkmn")).isEqualTo(RecoveryCodes.hash("ABCDEFGHJKMN")).isEqualTo(RecoveryCodes.hash(" abcdef ghjkmn "));
        assertThat(RecoveryCodes.hash("ABCDEF-GHJKMN")).isNotEqualTo(RecoveryCodes.hash("ABCDEF-GHJKMM"));
    }

    // ---- masking ----------------------------------------------------------------------------

    @Test
    void masksEmails() {
        assertThat(Masking.email("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(Masking.email("x")).isEqualTo("***");
        assertThat(Masking.email(null)).isEmpty();
        assertThat(Masking.email("")).isEmpty();
    }

    // ---- configuration defaults -------------------------------------------------------------

    @Test
    void defaultsMatchTheSecurityBaseline() {
        AuthProperties properties = new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("app.auth", AuthProperties.class);

        assertThat(properties.bcryptStrength()).isEqualTo(12);
        assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.mfaTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.refreshTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.resetTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.inviteTtl()).isEqualTo(Duration.ofHours(48));
        assertThat(properties.maxFailedAttempts()).isEqualTo(5);
        assertThat(properties.lockDuration()).isEqualTo(Duration.ofMinutes(15));

        PasswordEncoder encoder = new AuthConfig().passwordEncoder(properties);
        assertThat(encoder.encode("Correct-Horse-9-Staple")).startsWith("$2a$12$");
    }

    @Test
    void rejectsAnAbsurdBcryptCost() {
        Map<String, Object> low = Map.of("app.auth.bcrypt-strength", "3");
        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(low)).bindOrCreate("app.auth", AuthProperties.class)).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    // ---- key loading ------------------------------------------------------------------------

    private static AuthProperties keys(String jwt, String totp) {
        return new AuthProperties(jwt, totp, "http://localhost", "amanah-connect", Duration.ofMinutes(15), Duration.ofMinutes(5),
                Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofHours(48), 12, 5, Duration.ofMinutes(15));
    }

    @Test
    void productionRefusesToStartWithoutKeys() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        String goodTotp = java.util.Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> new AuthKeysConfig().authKeys(keys("", goodTotp), prod)).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new AuthKeysConfig().authKeys(keys("x".repeat(40), ""), prod)).hasMessageContaining("TOTP_ENC_KEY");
        assertThatThrownBy(() -> new AuthKeysConfig().authKeys(keys(null, null), new MockEnvironment())).as("no profile at all behaves like production").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void weakOrMalformedKeysAreRefusedEverywhere() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        String goodTotp = java.util.Base64.getEncoder().encodeToString(new byte[32]);

        assertThatThrownBy(() -> new AuthKeysConfig().authKeys(keys("too-short", goodTotp), local)).hasMessageContaining("at least 32");
        assertThatThrownBy(() -> new AuthKeysConfig().authKeys(keys("x".repeat(40), "not base64!!"), local)).hasMessageContaining("base64");
        assertThatThrownBy(() -> new AuthKeysConfig().authKeys(keys("x".repeat(40), java.util.Base64.getEncoder().encodeToString(new byte[16])), local)).hasMessageContaining("32 bytes");
    }

    @Test
    void localAndTestGenerateThrowawayKeysWhenUnset() {
        for (String profile : new String[] {"local", "test"}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profile);

            AuthKeys first = new AuthKeysConfig().authKeys(keys("", ""), env);
            AuthKeys second = new AuthKeysConfig().authKeys(keys(null, null), env);

            assertThat(first.jwtKey().getEncoded()).hasSize(48).isNotEqualTo(second.jwtKey().getEncoded());
            assertThat(first.totpKey().getEncoded()).hasSize(32).isNotEqualTo(second.totpKey().getEncoded());
        }
    }

    @Test
    void configuredKeysAreUsedAsGiven() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        byte[] totp = new byte[32];
        java.util.Arrays.fill(totp, (byte) 7);

        AuthKeys loaded = new AuthKeysConfig().authKeys(keys("s".repeat(40), java.util.Base64.getEncoder().encodeToString(totp)), prod);

        assertThat(new String(loaded.jwtKey().getEncoded())).isEqualTo("s".repeat(40));
        assertThat(loaded.totpKey().getEncoded()).isEqualTo(totp);
    }
}
