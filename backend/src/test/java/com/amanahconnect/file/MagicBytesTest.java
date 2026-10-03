package com.amanahconnect.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MagicBytesTest {

    private static final List<String> TYPES = List.of("image/png", "image/jpeg", "image/webp", "application/pdf");

    @Test
    void theSampleHeaderOfEachTypeIsRecognisedForThatTypeOnly() {
        for (String type : TYPES) {
            for (String other : TYPES) {
                assertThat(MagicBytes.matches(other, MagicBytes.sample(type))).as(type + " bytes as " + other).isEqualTo(type.equals(other));
            }
        }
    }

    @Test
    void realFileHeadersAreRecognised() {
        assertThat(MagicBytes.matches("image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52})).isTrue();
        assertThat(MagicBytes.matches("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB, 0, 0x43, 0, 3, 2, 2, 2, 2, 2, 3, 2, 2})).isTrue();
        assertThat(MagicBytes.matches("image/webp", "RIFF$\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isTrue();
        assertThat(MagicBytes.matches("application/pdf", "%PDF-1.4\n%âãÏÓ".getBytes(StandardCharsets.ISO_8859_1))).isTrue();
    }

    @Test
    void textMarkupAndScriptsAreNeverAnImageOrPdf() {
        for (String hostile : List.of("<html><script>alert(1)</script></html>", "<?xml version=\"1.0\"?><svg onload=alert(1)/>", "#!/bin/sh\nrm -rf /", "GIF89a", "PK\u0003\u0004zip", "MZ\u0090exe", "<!DOCTYPE html>")) {
            for (String type : TYPES) {
                assertThat(MagicBytes.matches(type, hostile.getBytes(StandardCharsets.ISO_8859_1))).as(hostile + " as " + type).isFalse();
            }
        }
    }

    @Test
    void shortOrEmptyOrUnknownIsRefused() {
        assertThat(MagicBytes.matches("image/png", new byte[0])).isFalse();
        assertThat(MagicBytes.matches("image/png", new byte[] {(byte) 0x89, 0x50})).isFalse();
        assertThat(MagicBytes.matches("image/webp", "RIFF".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(MagicBytes.matches("image/webp", "RIFF\0\0\0\0WAVE".getBytes(StandardCharsets.ISO_8859_1))).as("a WAV is not a WebP").isFalse();
        assertThat(MagicBytes.matches("text/html", "<html>".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(MagicBytes.matches("image/svg+xml", "<svg/>".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(MagicBytes.matches(null, MagicBytes.sample("image/png"))).isFalse();
        assertThat(MagicBytes.matches("image/png", null)).isFalse();
    }
}
