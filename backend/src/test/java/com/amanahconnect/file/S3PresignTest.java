package com.amanahconnect.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What the real S3 presigner puts in an upload URL: the signature must cover the content type and size, so the browser cannot send anything else. */
class S3PresignTest {

    private static String oldKey;
    private static String oldSecret;

    @BeforeAll
    static void credentials() {
        oldKey = System.getProperty("aws.accessKeyId");
        oldSecret = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "AKIATESTTESTTESTTEST");
        System.setProperty("aws.secretAccessKey", "test-secret-test-secret-test-secret-00");
    }

    @AfterAll
    static void restore() {
        if (oldKey == null) System.clearProperty("aws.accessKeyId"); else System.setProperty("aws.accessKeyId", oldKey);
        if (oldSecret == null) System.clearProperty("aws.secretAccessKey"); else System.setProperty("aws.secretAccessKey", oldSecret);
    }

    private S3ObjectStorage storage(String endpoint) {
        return new S3ObjectStorage(new StorageProperties("amanah-test-bucket", "ap-south-1", endpoint, 524288, 5242880, Duration.ofMinutes(10), Duration.ofMinutes(2), "", ""), Clock.systemUTC());
    }

    private static Map<String, String> query(String url) {
        Map<String, String> q = new java.util.LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8) : "");
        }
        return q;
    }

    @Test
    void anUploadUrlIsSignedForExactlyThatTypeAndSizeAndExpiresQuickly() {
        var upload = storage("").presignUpload("communities/c1/ledger/abc.png", "image/png", 20_000);

        Map<String, String> q = query(upload.url());
        assertThat(URI.create(upload.url()).getHost()).isEqualTo("amanah-test-bucket.s3.ap-south-1.amazonaws.com");
        assertThat(URI.create(upload.url()).getPath()).isEqualTo("/communities/c1/ledger/abc.png");
        assertThat(q.get("X-Amz-SignedHeaders")).contains("content-length").contains("content-type").contains("host");
        assertThat(q.get("X-Amz-Expires")).isEqualTo("600");
        assertThat(q.get("X-Amz-Algorithm")).isEqualTo("AWS4-HMAC-SHA256");
        assertThat(q).containsKey("X-Amz-Signature");
        assertThat(upload.headers()).containsEntry("Content-Type", "image/png").containsEntry("Content-Length", "20000");
        assertThat(upload.expiresAt()).isBefore(java.time.Instant.now().plusSeconds(601)).isAfter(java.time.Instant.now().plusSeconds(500));
    }

    @Test
    void aDifferentTypeOrSizeGivesADifferentSignature() {
        S3ObjectStorage s = storage("");
        String base = query(s.presignUpload("k/a.png", "image/png", 100).url()).get("X-Amz-Signature");

        assertThat(query(s.presignUpload("k/a.png", "image/jpeg", 100).url()).get("X-Amz-Signature")).isNotEqualTo(base);
        assertThat(query(s.presignUpload("k/a.png", "image/png", 101).url()).get("X-Amz-Signature")).isNotEqualTo(base);
        assertThat(query(s.presignUpload("k/b.png", "image/png", 100).url()).get("X-Amz-Signature")).isNotEqualTo(base);
    }

    @Test
    void aDownloadUrlIsShortLivedAndOnlyForReading() {
        String url = storage("").presignDownload("communities/c1/logo/abc.png");

        Map<String, String> q = query(url);
        assertThat(q.get("X-Amz-Expires")).isEqualTo("120");
        assertThat(q.get("X-Amz-SignedHeaders")).isEqualTo("host");
        assertThat(url).doesNotContain("content-type");
    }

    @Test
    void anS3CompatibleEndpointForLocalWorkUsesPathStyleUrls() {
        var upload = storage("http://localhost:9000").presignUpload("communities/c1/logo/abc.png", "image/png", 1000);

        assertThat(URI.create(upload.url()).getHost()).isEqualTo("localhost");
        assertThat(URI.create(upload.url()).getPort()).isEqualTo(9000);
        assertThat(URI.create(upload.url()).getPath()).isEqualTo("/amanah-test-bucket/communities/c1/logo/abc.png");
    }

    @Test
    void staticCredentialsAreUsedWhenGivenForLocalMinio() {
        S3ObjectStorage local = new S3ObjectStorage(new StorageProperties("amanah-local", "ap-south-1", "http://localhost:9000", 524288, 5242880, Duration.ofMinutes(10), Duration.ofMinutes(2), "minio-user", "minio-secret-123"), Clock.systemUTC());

        String url = local.presignUpload("communities/c1/logo/abc.png", "image/png", 1000).url();

        assertThat(query(url).get("X-Amz-Credential")).startsWith("minio-user/");
    }
}
