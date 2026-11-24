package com.amanahconnect.file;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * File storage ({@code app.storage.*}). Set {@code localPath} to keep files on this server's disk (phase 1: no S3). If it is
 * empty and {@code bucket} is set, S3 is used. With neither, anything that needs storage answers 503 STORAGE_UNAVAILABLE.
 *
 * @param localPath folder for files on local disk (receipts, logos, attachments, exports); created if missing; back it up with the database
 * @param signingKey secret that signs the upload and download links of local storage; if empty a random one is made at every start (links then stop working on restart)
 * @param bucket the S3 bucket for uploads (never public: files are served by short-lived signed URLs)
 * @param region AWS region
 * @param endpoint optional S3-compatible endpoint (MinIO, LocalStack) for local work
 * @param logoMaxBytes largest community logo accepted
 * @param attachmentMaxBytes largest receipt image or PDF attached to a ledger entry
 * @param uploadTtl how long a signed upload URL works
 * @param downloadTtl how long a signed download URL works
 * @param accessKey static credentials, for MinIO and other local S3-compatible servers only; leave empty on AWS (the instance role is used)
 * @param secretKey see {@code accessKey}
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @DefaultValue("") String localPath,
        @DefaultValue("") String signingKey,
        @DefaultValue("") String bucket,
        @DefaultValue("ap-south-1") String region,
        @DefaultValue("") String endpoint,
        @DefaultValue("524288") long logoMaxBytes,
        @DefaultValue("5242880") long attachmentMaxBytes,
        @DefaultValue("PT10M") Duration uploadTtl,
        @DefaultValue("PT10M") Duration downloadTtl,
        @DefaultValue("") String accessKey,
        @DefaultValue("") String secretKey) {

    /** S3-only settings (no local folder): what the code and tests that predate local-disk storage use. */
    public static StorageProperties s3(
            String bucket, String region, String endpoint, long logoMaxBytes, long attachmentMaxBytes,
            Duration uploadTtl, Duration downloadTtl, String accessKey, String secretKey) {
        return new StorageProperties("", "", bucket, region, endpoint, logoMaxBytes, attachmentMaxBytes, uploadTtl, downloadTtl, accessKey, secretKey);
    }
}
