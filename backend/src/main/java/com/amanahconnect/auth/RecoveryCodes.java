package com.amanahconnect.auth;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Single-use 2FA recovery codes: 12 unambiguous characters (60 bits), shown once, stored hashed. */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    public static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            StringBuilder code = new StringBuilder(LENGTH + 1);
            for (int j = 0; j < LENGTH; j++) {
                if (j == LENGTH / 2) {
                    code.append('-');
                }
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(code.toString());
        }
        return codes;
    }

    /** Case, spaces and hyphens do not matter when a user types a code back. */
    public static String normalize(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static String hash(String code) {
        return Tokens.sha256Hex(normalize(code));
    }
}
