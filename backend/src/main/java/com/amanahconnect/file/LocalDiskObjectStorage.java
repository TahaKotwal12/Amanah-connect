package com.amanahconnect.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Files on this server's own disk, under one folder ({@code app.storage.local-path}). Same contract as
 * {@link S3ObjectStorage}, so nothing else in the application knows the difference.
 *
 * <p>There is no S3 to hand out presigned URLs, so the "presigned" URLs here point back at this application
 * ({@link LocalFileController}) and carry an HMAC signature over what they allow: the operation, the key, the expiry and,
 * for an upload, the exact size and content type. The folder is never served directly, a key can never leave it (see
 * {@link #resolve}), and each file has a small {@code .meta} file beside it holding its content type.
 *
 * <p>Back this folder up with the database: receipts, logos and attachments live only here.
 */
public final class LocalDiskObjectStorage implements ObjectStorage {

    static final String UPLOAD_PATH = "/api/v1/public/files/upload";
    static final String DOWNLOAD_PATH = "/api/v1/public/files/download";
    private static final String META_SUFFIX = ".meta";

    private final Path root;
    private final byte[] signingKey;
    private final StorageProperties properties;
    private final Clock clock;

    public LocalDiskObjectStorage(StorageProperties properties, byte[] signingKey, Clock clock) {
        this.properties = properties;
        this.signingKey = signingKey.clone();
        this.clock = clock;
        this.root = Path.of(properties.localPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("app.storage.local-path is not usable: " + root, e);
        }
    }

    // ---- paths ---------------------------------------------------------------------------------------

    /** The file for a key, or an exception if the key could point outside the storage folder. Keys are built by the server; this is the second lock. */
    Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("\\") || key.contains("\0") || key.startsWith("/")) {
            throw new IllegalArgumentException("invalid storage key");
        }
        for (String part : key.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("invalid storage key");
            }
        }
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root) || file.endsWith(META_SUFFIX)) {
            throw new IllegalArgumentException("invalid storage key");
        }
        return file;
    }

    private static Path metaOf(Path file) {
        return file.resolveSibling(file.getFileName() + META_SUFFIX);
    }

    // ---- signed URLs ---------------------------------------------------------------------------------

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long size) {
        resolve(key);
        Instant expires = clock.instant().plus(properties.uploadTtl());
        String sig = sign("upload", key, expires.getEpochSecond(), size, contentType);
        String url = UPLOAD_PATH + "?key=" + enc(key) + "&exp=" + expires.getEpochSecond() + "&size=" + size + "&type=" + enc(contentType) + "&sig=" + sig;
        return new PresignedUpload(url, Map.of("Content-Type", contentType), expires);
    }

    @Override
    public String presignDownload(String key) {
        resolve(key);
        long expires = clock.instant().plus(properties.downloadTtl()).getEpochSecond();
        return DOWNLOAD_PATH + "?key=" + enc(key) + "&exp=" + expires + "&sig=" + sign("download", key, expires, 0, "");
    }

    /** True when the signature is genuine and not expired. Constant-time comparison. */
    boolean verify(String operation, String key, long expiresEpoch, long size, String contentType, String signature) {
        if (clock.instant().getEpochSecond() > expiresEpoch || signature == null) {
            return false;
        }
        byte[] expected = sign(operation, key, expiresEpoch, size, contentType).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String operation, String key, long expiresEpoch, long size, String contentType) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            String message = operation + "\n" + key + "\n" + expiresEpoch + "\n" + size + "\n" + contentType;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---- reading and writing -------------------------------------------------------------------------

    @Override
    public Optional<ObjectInfo> head(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ObjectInfo(Files.size(file), contentTypeOf(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String contentTypeOf(Path file) throws IOException {
        Path meta = metaOf(file);
        return Files.isRegularFile(meta) ? Files.readString(meta, StandardCharsets.UTF_8).trim() : "application/octet-stream";
    }

    /** Writes a file from a stream: to a temporary file in the same folder first, then moved into place, so a half upload is never visible. */
    void store(String key, InputStream data, String contentType) throws IOException {
        Path file = resolve(key);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".upload-", ".tmp");
        try {
            Files.copy(data, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(metaOf(file), contentType, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            store(key, in, contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void putFile(String key, Path source, String contentType) {
        try (InputStream in = Files.newInputStream(source)) {
            store(key, in, contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path file = resolve(key);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> getRange(String key, long from, long toInclusive) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long end = Math.min(toInclusive, raf.length() - 1);
            if (from > end) {
                return Optional.of(new byte[0]);
            }
            byte[] bytes = new byte[(int) (end - from + 1)];
            raf.seek(from);
            raf.readFully(bytes);
            return Optional.of(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        Path file = resolve(key);
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(metaOf(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file itself, for streaming a download. */
    Optional<Path> file(String key) {
        Path file = resolve(key);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    String contentType(Path file) {
        try {
            return contentTypeOf(file);
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }
}
