package com.amanahconnect.common;

/** Clean-up of text that arrives from the public. */
public final class Text {

    private Text() {}

    /** Single-line text: control characters (including newlines) become spaces, runs collapse, ends trimmed. */
    public static String singleLine(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
