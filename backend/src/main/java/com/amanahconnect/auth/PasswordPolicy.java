package com.amanahconnect.auth;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Minimum 10 characters plus a basic strength check: mixed character classes (or a long
 * passphrase), no trivially common password, no repeated or tiny character set, and not built from
 * the user's own email name. BCrypt only reads 72 bytes, so longer passwords are refused instead of
 * being silently truncated.
 */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 128;
    static final int MAX_BYTES = 72;

    private static final Set<String> COMMON_BASES =
            Set.of(
                    "password", "passw0rd", "qwerty", "qwertyuiop", "qwertyuiopasdf", "letmein", "welcome",
                    "admin", "administrator", "iloveyou", "monkey", "dragon", "football", "baseball",
                    "changeme", "trustno", "abc", "amanah", "amanahconnect", "community", "superadmin",
                    "default", "secret", "master", "login", "princess", "sunshine", "whatever");

    public void validate(String password, String email) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            problems.add("Use at least " + MIN_LENGTH + " characters.");
        } else {
            if (password.length() > MAX_LENGTH || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                problems.add("Use at most " + MAX_BYTES + " bytes (about 72 characters).");
            }
            checkStrength(password, email, problems);
        }
        if (!problems.isEmpty()) {
            throw new ApiException(
                    ErrorCode.PASSWORD_POLICY_VIOLATION, "The password does not meet the policy.", problems);
        }
    }

    private static void checkStrength(String password, String email, List<String> problems) {
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean other = false;
        for (char c : password.toCharArray()) {
            if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                other = true;
            }
        }
        int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
        boolean longPassphrase = password.length() >= 16 && classes >= 2;
        if (classes < 3 && !longPassphrase) {
            problems.add("Mix at least three of: lower case, upper case, digits and symbols, or use a longer passphrase.");
        }
        if (password.chars().distinct().count() < 5) {
            problems.add("Use a wider variety of characters.");
        }
        String lowered = password.toLowerCase(Locale.ROOT);
        String lettersOnly = lowered.replaceAll("[^a-z]", "");
        if (COMMON_BASES.contains(lowered) || COMMON_BASES.contains(lettersOnly) || isSequence(lowered)) {
            problems.add("That password is too common or predictable.");
        }
        if (email != null) {
            String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            local = local.toLowerCase(Locale.ROOT);
            if (local.length() >= 4 && lowered.contains(local)) {
                problems.add("Do not include your email name in the password.");
            }
        }
    }

    private static boolean isSequence(String value) {
        return "01234567890123456789".contains(value) || "abcdefghijklmnopqrstuvwxyz".contains(value);
    }
}
