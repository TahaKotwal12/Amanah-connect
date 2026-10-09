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

    /** Like a browser upload with real content: stores these exact bytes under the declared type (to test files that lie about their type). */
    public void putRaw(String key, String contentType, byte[] bytes) {
        objects.put(key, new ObjectInfo(bytes.length, contentType));
        contents.put(key, bytes);
    }

    @Override
    public void putFile(String key, java.nio.file.Path file, String contentType) {
        if (failPuts) {
            throw new IllegalStateException("storage is down");
        }
        try {
            put(key, java.nio.file.Files.readAllBytes(file), contentType);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> getRange(String key, long from, long toInclusive) {
        ObjectInfo info = objects.get(key);
        if (info == null) return Optional.empty();
        byte[] real = contents.get(key);
        // An object a test only "uploaded" by metadata gets a header that matches its declared type.
        byte[] all = real != null ? real : com.amanahconnect.file.MagicBytes.sample(info.contentType());
        int end = (int) Math.min(all.length, toInclusive + 1);
        return Optional.of(java.util.Arrays.copyOfRange(all, (int) Math.min(from, end), end));
    }

    @Override
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(contents.get(key));
    }
}
