package com.amanahconnect.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDiskObjectStorageTest {

    @TempDir Path dir;

    private LocalDiskObjectStorage storage(Clock clock) {
        StorageProperties p = new StorageProperties(dir.toString(), "", "", "ap-south-1", "", 524288, 5242880, Duration.ofMinutes(10), Duration.ofMinutes(10), "", "");
        return new LocalDiskObjectStorage(p, "test-signing-key-test-signing-key".getBytes(StandardCharsets.UTF_8), clock);
    }

    private static long expiryOf(String url) {
        int from = url.indexOf("&exp=") + 5;
        int to = url.indexOf('&', from);
        return Long.parseLong(url.substring(from, to));
    }

    private final Clock now = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void storesAndReadsBackBytesWithTheirContentType() {
        LocalDiskObjectStorage s = storage(now);
        s.put("communities/c1/receipts/r1.pdf", "%PDF-1.7 hello".getBytes(StandardCharsets.UTF_8), "application/pdf");
        assertThat(s.get("communities/c1/receipts/r1.pdf")).hasValueSatisfying(b -> assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("%PDF-1.7 hello"));
        assertThat(s.head("communities/c1/receipts/r1.pdf")).hasValueSatisfying(i -> {
            assertThat(i.size()).isEqualTo(14);
            assertThat(i.contentType()).isEqualTo("application/pdf");
        });
        assertThat(s.getRange("communities/c1/receipts/r1.pdf", 0, 3)).hasValueSatisfying(b -> assertThat(new String(b, StandardCharsets.UTF_8)).isEqualTo("%PDF"));
        assertThat(s.getRange("communities/c1/receipts/r1.pdf", 0, 9999)).hasValueSatisfying(b -> assertThat(b).hasSize(14));
    }

    @Test
    void missingFilesAreEmptyNotErrors() {
        LocalDiskObjectStorage s = storage(now);
        assertThat(s.get("nothing/here")).isEmpty();
        assertThat(s.head("nothing/here")).isEmpty();
        assertThat(s.getRange("nothing/here", 0, 3)).isEmpty();
        s.delete("nothing/here"); // harmless
    }

    @Test
    void deleteRemovesTheFileAndItsMetaFile() throws Exception {
        LocalDiskObjectStorage s = storage(now);
        s.put("a/b.png", new byte[] {1, 2, 3}, "image/png");
        s.delete("a/b.png");
        assertThat(s.get("a/b.png")).isEmpty();
        try (var files = Files.list(dir.resolve("a"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void aKeyCanNeverLeaveTheStorageFolder() {
        LocalDiskObjectStorage s = storage(now);
        for (String bad : Arrays.asList("../secret", "a/../../secret", "/etc/passwd", "a\b", "a//b", "a/./b", "", "x.meta/..", "a\0b")) {
            assertThatThrownBy(() -> s.put(bad, new byte[] {1}, "image/png")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(dir.getParent().resolve("secret")).doesNotExist();
    }

    @Test
    void signedLinksAreGenuineOnlyForExactlyWhatWasSigned() {
        LocalDiskObjectStorage s = storage(now);
        var up = s.presignUpload("communities/c1/logo/l1.png", "image/png", 1234);
        String sig = up.url().substring(up.url().indexOf("&sig=") + 5);
        long exp = up.expiresAt().getEpochSecond();
        assertThat(s.verify("upload", "communities/c1/logo/l1.png", exp, 1234, "image/png", sig)).isTrue();
        // every part of what was signed matters
        assertThat(s.verify("upload", "communities/c2/logo/l1.png", exp, 1234, "image/png", sig)).isFalse();
        assertThat(s.verify("upload", "communities/c1/logo/l1.png", exp, 9999, "image/png", sig)).isFalse();
        assertThat(s.verify("upload", "communities/c1/logo/l1.png", exp, 1234, "application/pdf", sig)).isFalse();
        assertThat(s.verify("upload", "communities/c1/logo/l1.png", exp + 1, 1234, "image/png", sig)).isFalse();
        // an upload link is not a download link
        assertThat(s.verify("download", "communities/c1/logo/l1.png", exp, 1234, "image/png", sig)).isFalse();
        assertThat(s.verify("upload", "communities/c1/logo/l1.png", exp, 1234, "image/png", "forged")).isFalse();
    }

    @Test
    void linksStopWorkingAfterTheyExpire() {
        LocalDiskObjectStorage s = storage(now);
        String url = s.presignDownload("a/b.pdf");
        long exp = expiryOf(url);
        String sig = url.substring(url.indexOf("&sig=") + 5);
        assertThat(s.verify("download", "a/b.pdf", exp, 0, "", sig)).isTrue();
        LocalDiskObjectStorage later = storage(Clock.fixed(now.instant().plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
        assertThat(later.verify("download", "a/b.pdf", exp, 0, "", sig)).isFalse();
    }

    @Test
    void linksFromAnotherServersKeyAreRejected() {
        String url = storage(now).presignDownload("a/b.pdf");
        long exp = expiryOf(url);
        String sig = url.substring(url.indexOf("&sig=") + 5);
        StorageProperties p = new StorageProperties(dir.toString(), "", "", "ap-south-1", "", 524288, 5242880, Duration.ofMinutes(10), Duration.ofMinutes(10), "", "");
        var other = new LocalDiskObjectStorage(p, "a-completely-different-key-abcdefgh".getBytes(StandardCharsets.UTF_8), now);
        assertThat(other.verify("download", "a/b.pdf", exp, 0, "", sig)).isFalse();
    }
}
