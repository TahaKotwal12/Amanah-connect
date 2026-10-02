package com.amanahconnect.common;

/** Masks personal data before it goes into logs or audit records. */
public final class Masking {

    private Masking() {}

    /** {@code alice@example.com} becomes {@code a***@example.com}. */
    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
