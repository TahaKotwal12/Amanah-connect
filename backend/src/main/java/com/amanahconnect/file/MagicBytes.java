package com.amanahconnect.file;

/** Checks that a file's first bytes are what its declared type says, so a renamed HTML or script file is not stored as an image. */
public final class MagicBytes {

    /** Bytes needed to decide for every supported type. */
    public static final int HEAD_BYTES = 16;

    private MagicBytes() {}

    public static boolean matches(String contentType, byte[] head) {
        if (contentType == null || head == null) return false;
        return switch (contentType) {
            case "image/png" -> startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case "image/webp" -> head.length >= 12 && startsWith(head, 'R', 'I', 'F', 'F') && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            case "application/pdf" -> startsWith(head, '%', 'P', 'D', 'F', '-');
            default -> false;
        };
    }

    /** A header that passes {@link #matches} for the type (used by test doubles that store no real bytes). */
    public static byte[] sample(String contentType) {
        return switch (contentType) {
            case "image/png" -> new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0};
            case "image/jpeg" -> new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            case "image/webp" -> new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 0, 0, 0, 0};
            case "application/pdf" -> new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '7', '\n', 0, 0, 0, 0, 0, 0, 0};
            default -> new byte[HEAD_BYTES];
        };
    }

    private static boolean startsWith(byte[] head, int... expected) {
        if (head.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((head[i] & 0xFF) != expected[i]) return false;
        }
        return true;
    }
}
