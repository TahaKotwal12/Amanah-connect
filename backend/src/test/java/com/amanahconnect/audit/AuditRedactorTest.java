package com.amanahconnect.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.auth.User;
import com.amanahconnect.auth.UserRole;
import jakarta.persistence.Entity;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import tools.jackson.databind.json.JsonMapper;

class AuditRedactorTest {

    private final AuditRedactor redactor = new AuditRedactor(JsonMapper.builder().build());

    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl";

    @ParameterizedTest
    @ValueSource(strings = {
        "password", "passwordHash", "password_hash", "PasswordHash", "password-hash", "PASSWORD", "newPassword", "currentPassword", "oldPassword",
        "accessToken", "refresh_token", "mfaToken", "token", "tokenHash", "token_hash", "idToken",
        "secret", "clientSecret", "totpSecret", "totp_secret_enc", "totpSecretEnc",
        "recoveryCode", "recoveryCodes", "recovery_codes", "codeHash",
        "Authorization", "Cookie", "set-cookie", "apiKey", "api_key", "privateKey", "credentials",
        "otp", "totp", "mfaCode", "otpauthUri", "pin", "salt"})
    void redactsSensitiveKeysWhateverTheirSpelling(String key) {
        Map<String, Object> out = redactor.redactToMap(Map.of(key, "super-secret-value"));

        assertThat(out).as(key).containsEntry(key, AuditRedactor.REDACTED);
        assertThat(AuditRedactor.isSensitiveKey(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fullName", "email", "amount", "status", "communityId", "memberNo", "dueDate", "role", "footprint", "action", "totpEnabled", "mustSetup2fa", "failedAttempts"})
    void keepsOrdinaryFields(String key) {
        assertThat(redactor.redactToMap(Map.of(key, "visible"))).containsEntry(key, "visible");
        assertThat(AuditRedactor.isSensitiveKey(key)).isFalse();
    }

    @Test
    void redactsAtAnyDepthInMapsListsAndArrays() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "Asha");
        input.put("profile", Map.of("login", Map.of("passwordHash", "$2a$12$abc", "attempts", 3)));
        input.put("sessions", List.of(Map.of("refreshToken", "abc", "device", "phone"), Map.of("tokenHash", "h", "device", "laptop")));
        input.put("codes", new Object[] {Map.of("recoveryCode", "AAAA-BBBB")});

        Map<String, Object> out = redactor.redactToMap(input);

        String text = out.toString();
        assertThat(text).doesNotContain("$2a$12$abc").doesNotContain("AAAA-BBBB").contains("Asha", "phone", "laptop");
        assertThat(((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) out.get("profile")).get("login"))).get("attempts")).isEqualTo(3);
        assertThat(text.split("\\[REDACTED]", -1).length - 1).isEqualTo(4);
    }

    @Test
    void redactsSecretLookingValuesEvenUnderInnocentKeys() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("note", JWT);
        input.put("header", "Bearer abc.def.ghi");
        input.put("link", "otpauth://totp/x?secret=ABC");
        input.put("raw", "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0uVw"); // 43 url-safe chars: a 256-bit token
        input.put("digest", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        input.put("id", "6f1c0c1e-2f64-4c53-9d6b-0a7f3a1b2c3d"); // a UUID is not a secret
        input.put("memo", "paid in cash by hand");

        Map<String, Object> out = redactor.redactToMap(input);

        for (String key : List.of("note", "header", "link", "raw", "digest")) {
            assertThat(out.get(key)).as(key).isEqualTo(AuditRedactor.REDACTED);
        }
        assertThat(out.get("id")).isEqualTo("6f1c0c1e-2f64-4c53-9d6b-0a7f3a1b2c3d");
        assertThat(out.get("memo")).isEqualTo("paid in cash by hand");
    }

    @Test
    void redactsAJpaEntityAsAMapWithoutLeakingItsSecrets() {
        User user = new User();
        user.setEmail("asha@example.test");
        user.setFullName("Asha");
        user.setRole(UserRole.COMMUNITY_ADMIN);
        user.setPasswordHash("$2a$12$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ0123");
        user.setTotpSecretEnc("c2VjcmV0LWNpcGhlcnRleHQ=");
        user.setTotpEnabled(true);

        Map<String, Object> out = redactor.redactToMap(user);

        assertThat(out).containsEntry("passwordHash", AuditRedactor.REDACTED).containsEntry("totpSecretEnc", AuditRedactor.REDACTED);
        assertThat(out).containsEntry("email", "asha@example.test").containsEntry("totpEnabled", true);
        assertThat(out.toString()).doesNotContain("$2a$").doesNotContain("c2VjcmV0");
    }

    @Test
    void convertsRecordsAndWrapsScalarsAndLists() {
        record Dto(String name, Instant when, String password) {}

        Map<String, Object> dto = redactor.redactToMap(new Dto("Asha", Instant.parse("2026-01-01T00:00:00Z"), "hunter2"));
        assertThat(dto).containsEntry("name", "Asha").containsEntry("when", "2026-01-01T00:00:00Z").containsEntry("password", AuditRedactor.REDACTED);

        assertThat(redactor.redactToMap("just text")).containsExactly(Map.entry("value", "just text"));
        assertThat(redactor.redactToMap(List.of(JWT, "ok"))).containsEntry("value", List.of(AuditRedactor.REDACTED, "ok"));
        assertThat(redactor.redactToMap(42)).containsEntry("value", 42);
    }

    @Test
    void handlesNullsAndCapsHugeStrings() {
        assertThat(redactor.redactToMap(null)).isNull();
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("password", null);
        withNull.put("note", null);
        assertThat(redactor.redactToMap(withNull)).containsEntry("password", null).containsEntry("note", null);

        String huge = "x".repeat(10_000);
        String stored = (String) redactor.redactToMap(Map.of("memo", huge)).get("memo");
        assertThat(stored).hasSizeLessThan(2_100).endsWith("[truncated]");
    }

    @Test
    void anObjectThatCannotBeConvertedNeverBreaksAuditingAndNeverLeaks() {
        Object hostile = new Object() {
            @SuppressWarnings("unused")
            public String getPassword() {
                throw new IllegalStateException("boom");
            }
        };

        Map<String, Object> out = redactor.redactToMap(hostile);

        assertThat(out).containsKey("_unserializable");
        assertThat(out.toString()).doesNotContain("boom");
    }

    @Test
    void veryDeepStructuresAreTruncatedNotRecursedForever() {
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < 30; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put("level", next);
            cursor = next;
        }
        cursor.put("password", "deep-secret");

        assertThat(redactor.redactToMap(deep).toString()).doesNotContain("deep-secret").contains("[TRUNCATED]");
    }

    /**
     * The safety net for future columns: every entity field whose name looks like a secret must be
     * recognised by the redactor, so adding {@code apiToken} or {@code passwordResetCode} to an entity
     * cannot start leaking into the audit log without this test failing.
     */
    @Test
    void everySecretLookingEntityFieldIsCoveredByTheRedactor() throws Exception {
        Pattern looksSecret = Pattern.compile("(?i)(passw|secret|token|hash|recover|totpcode|mfacode)");
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<String> uncovered = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        int entities = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.amanahconnect")) {
            entities++;
            for (Class<?> type = Class.forName(definition.getBeanClassName()); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !looksSecret.matcher(field.getName()).find()) {
                        continue;
                    }
                    String name = definition.getBeanClassName().substring(definition.getBeanClassName().lastIndexOf('.') + 1) + "." + field.getName();
                    checked.add(name);
                    if (!AuditRedactor.isSensitiveKey(field.getName())) {
                        uncovered.add(name);
                    }
                }
            }
        }

        assertThat(entities).as("entities scanned").isGreaterThanOrEqualTo(25);
        assertThat(checked).as("the scan found the known secret columns")
                .contains("User.passwordHash", "User.totpSecretEnc", "RefreshToken.tokenHash", "RecoveryCode.codeHash", "MemberInvite.tokenHash", "AuthToken.tokenHash");
        assertThat(uncovered).as("secret-looking entity fields the redactor would NOT redact").isEmpty();
    }
}
