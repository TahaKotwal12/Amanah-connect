package com.amanahconnect.file;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Object storage ({@code app.storage.*}). Leave {@code bucket} empty to run without it: anything that needs
 * storage then answers 503 STORAGE_UNAVAILABLE.
 *
 * @param bucket the S3 bucket for uploads (never public: files are served by short-lived signed URLs)
 * @param region AWS region
 * @param endpoint optional S3-compatible endpoint (MinIO, LocalStack) for local work
 * @param logoMaxBytes largest community logo accepted
 * @param uploadTtl how long a signed upload URL works
 * @param downloadTtl how long a signed download URL works
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @DefaultValue("") String bucket,
        @DefaultValue("ap-south-1") String region,
        @DefaultValue("") String endpoint,
        @DefaultValue("524288") long logoMaxBytes,
        @DefaultValue("PT10M") Duration uploadTtl,
        @DefaultValue("PT10M") Duration downloadTtl) {}
