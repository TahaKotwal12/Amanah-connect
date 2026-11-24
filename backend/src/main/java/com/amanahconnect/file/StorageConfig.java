package com.amanahconnect.file;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    /** The real thing when a bucket is configured; otherwise a stand-in that says storage is unavailable. */
    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    ObjectStorage objectStorage(StorageProperties properties, Clock clock) {
        if (!properties.localPath().isBlank()) {
            byte[] key = properties.signingKey().isBlank() ? randomKey() : properties.signingKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new LocalDiskObjectStorage(properties, key, clock);
        }
        if (properties.bucket().isBlank()) {
            return new UnconfiguredStorage();
        }
        return new S3ObjectStorage(properties, clock);
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }

    private static final class UnconfiguredStorage implements ObjectStorage {
        private static ApiException unavailable() {
            return new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "File storage is not configured on this server.");
        }

        @Override
        public PresignedUpload presignUpload(String key, String contentType, long size) {
            throw unavailable();
        }

        @Override
        public String presignDownload(String key) {
            throw unavailable();
        }

        @Override
        public Optional<ObjectInfo> head(String key) {
            throw unavailable();
        }

        @Override
        public void delete(String key) {
            // nothing was ever stored
        }

        @Override
        public void put(String key, byte[] bytes, String contentType) {
            throw unavailable();
        }

        @Override
        public Optional<byte[]> get(String key) {
            throw unavailable();
        }

        @Override
        public void putFile(String key, java.nio.file.Path file, String contentType) {
            throw unavailable();
        }

        @Override
        public Optional<byte[]> getRange(String key, long from, long toInclusive) {
            throw unavailable();
        }
    }
}
