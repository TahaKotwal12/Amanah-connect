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
    private final Map<String, byte[]> contents = new ConcurrentHashMap<>();
    /** Set to make every put fail, to prove that a storage outage never fails a payment. */
    public volatile boolean failPuts;
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
        contents.remove(key);
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        if (failPuts) {
            throw new IllegalStateException("storage is down");
        }
        objects.put(key, new ObjectInfo(bytes.length, contentType));
        contents.put(key, bytes);
    }

    @Override
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(contents.get(key));
    }
}
