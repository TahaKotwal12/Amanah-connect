package com.amanahconnect.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();
    private static final String EMAIL = "priya.sharma@example.com";

    @ParameterizedTest
    @ValueSource(strings = {"Correct-Horse-9-Staple", "xK9#mQ2vLp", "Tr1cky!Pass", "a long passphrase with spaces", "Winter2026Garden!"})
    void acceptsStrongPasswords(String password) {
        assertThatCode(() -> policy.validate(password, EMAIL)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "Ab1!", "123456789"})
    void rejectsPasswordsUnderTenCharacters(String password) {
        assertThatThrownBy(() -> policy.validate(password, EMAIL))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
                    assertThat(e.details()).anyMatch(d -> d.contains("at least 10"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Password123!", "password1234", "Qwertyuiop12", "Administrator1!", "Amanah-Connect-1", "1234567890", "abcdefghijk"})
    void rejectsCommonOrPredictablePasswords(String password) {
        assertThatThrownBy(() -> policy.validate(password, EMAIL)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alllowercaseletters", "ALLUPPERCASELETTERS", "onlylettersnodigits"})
    void rejectsSingleClassPasswordsEvenWhenLong(String password) {
        // 16+ characters with two or more classes is a passphrase; one class is not enough
        assertThatThrownBy(() -> policy.validate(password, EMAIL)).isInstanceOf(ApiException.class);
    }

    @Test
    void aLongTwoClassPassphraseIsAllowed() {
        assertThatCode(() -> policy.validate("purple monkeys dishwasher", EMAIL)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("Staple-correct-horse-battery", EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void rejectsLowVarietyPasswords() {
        assertThatThrownBy(() -> policy.validate("aaaaaaaaaaA1!", EMAIL)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("A1!A1!A1!A1!", EMAIL)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsPasswordsContainingTheEmailName() {
        assertThatThrownBy(() -> policy.validate("Priya.Sharma-2026!", EMAIL))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).anyMatch(d -> d.contains("email name")));
        assertThatCode(() -> policy.validate("Priya-2026-Garden!", EMAIL)).as("a short unrelated part is fine").doesNotThrowAnyException();
    }

    @Test
    void refusesPasswordsBcryptWouldSilentlyTruncate() {
        String varied = "Aa1!Bb2@Cc3#Dd4$Ee5%Ff6^Gg7&Hh8*"; // 32 distinct-ish characters
        String tooLong = varied.repeat(3); // 96 bytes
        assertThatThrownBy(() -> policy.validate(tooLong, EMAIL))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).anyMatch(d -> d.contains("72")));
        assertThatCode(() -> policy.validate(varied.repeat(2) + "Ii9(", EMAIL)).as("68 bytes is fine").doesNotThrowAnyException();
    }

    @Test
    void multiByteCharactersCountAsBytes() {
        // 30 characters but 90 bytes
        assertThatThrownBy(() -> policy.validate("日本語パスワード1!Aa".repeat(3), EMAIL)).isInstanceOf(ApiException.class);
    }

    @Test
    void theErrorNeverEchoesThePassword() {
        assertThatThrownBy(() -> policy.validate("TopSecretX", "x@y.z"))
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("TopSecretX");
                    assertThat(((ApiException) e).details().toString()).doesNotContain("TopSecretX");
                });
    }

    @Test
    void handlesNullInputs() {
        assertThatThrownBy(() -> policy.validate(null, EMAIL)).isInstanceOf(ApiException.class);
        assertThatCode(() -> policy.validate("Correct-Horse-9-Staple", null)).doesNotThrowAnyException();
    }
}
