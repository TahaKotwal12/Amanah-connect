package com.amanahconnect.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class S3TimeoutsTest {

    @Test
    void noS3CallCanHangAWorkerThread() {
        S3ObjectStorage storage = new S3ObjectStorage(
                new StorageProperties("bucket", "ap-south-1", "http://localhost:9", 1, 1, Duration.ofMinutes(1), Duration.ofMinutes(1), "key", "secret"), Clock.systemUTC());

        var config = storage.client().serviceClientConfiguration().overrideConfiguration();

        assertThat(config.apiCallAttemptTimeout()).hasValue(Duration.ofSeconds(30));
        assertThat(config.apiCallTimeout()).hasValue(Duration.ofMinutes(5));
    }
}
