package com.amanahconnect.support;

import com.amanahconnect.file.ObjectStorage;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for S3 in tests: signs nothing real, remembers what a test "uploaded". */
@Component
@Primary
public class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, ObjectInfo> objects = new ConcurrentHashMap<>();
    public final Map<String, Long> signedSizes = new ConcurrentHashMap<>();

    /** Simulates the browser's PUT to the signed URL. */
    public void put(String key, String contentType, long size) {
        objects.put(key, new ObjectInfo(size, contentType));
    }

    public boolean has(String key) {
        return objects.containsKey(key);
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long size) {
        signedSizes.put(key, size);
        return new PresignedUpload("https://storage.example.test/upload/" + key + "?sig=test", Map.of("Content-Type", contentType, "Content-Length", Long.toString(size)), Instant.now().plusSeconds(600));
    }

    @Override
    public String presignDownload(String key) {
        return "https://storage.example.test/download/" + key + "?sig=test";
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        return Optional.ofNullable(objects.get(key));
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
