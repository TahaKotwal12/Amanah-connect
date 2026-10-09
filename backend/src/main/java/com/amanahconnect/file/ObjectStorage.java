package com.amanahconnect.file;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** The little the application needs from object storage. Keys are always built by the server, never by a client. */
public interface ObjectStorage {

    /** A URL the browser PUTs the file to, and the headers it must send with it exactly. */
    record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {}

    record ObjectInfo(long size, String contentType) {}

    /** Signs an upload of exactly {@code size} bytes of {@code contentType} to {@code key}. */
    PresignedUpload presignUpload(String key, String contentType, long size);

    /** A short-lived URL to read the object. */
    String presignDownload(String key);

    /** Size and type of a stored object, or empty if it is not there. */
    Optional<ObjectInfo> head(String key);

    void delete(String key);

    /** The first bytes of an object (inclusive range), enough to check its real type; empty if there is no such object. */
    Optional<byte[]> getRange(String key, long from, long toInclusive);

    /** Stores bytes the server produced itself (a receipt PDF). */
    void put(String key, byte[] bytes, String contentType);

    /** Stores a file the server produced (a data export) without holding it in memory. */
    void putFile(String key, java.nio.file.Path file, String contentType);

    /** The stored bytes, or empty if there is no such object. */
    Optional<byte[]> get(String key);
}
