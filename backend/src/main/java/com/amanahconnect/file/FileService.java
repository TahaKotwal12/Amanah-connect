package com.amanahconnect.file;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.plan.PlanLimitService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every file that goes to object storage goes through here.
 *
 * <ul>
 *   <li><b>Upload</b>: the server builds the key ({@code communities/<communityId>/<folder>/<uuid>.<ext>}, never a client's), checks the
 *       content type against the kind's allow-list and the size against its limit and the plan's storage allowance, and returns a presigned
 *       PUT that is only good for exactly that type and size.</li>
 *   <li><b>Accept</b> (after the browser has uploaded): the key must be one issued for this community and kind; the object must exist; its
 *       stored type and size must be allowed; its first bytes must really be that type (so a renamed HTML or script file is refused); and it must
 *       fit in the plan's storage allowance. A file that fails is deleted. Accepted files are recorded, and the sum of a community's recorded files is
 *       its storage usage.</li>
 *   <li><b>Download</b>: short-lived presigned GET URLs only; the bucket is never public.</li>
 * </ul>
 */
@Service
@Transactional
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** What the browser needs to upload: PUT the file to {@code uploadUrl} with exactly these headers. */
    public record Upload(String uploadUrl, String method, Map<String, String> headers, String key, Instant expiresAt, long maxBytes) {}

    private final ObjectStorage storage;
    private final StorageProperties properties;
    private final StoredFileRepository files;
    private final PlanLimitService planLimits;
    private final NamedParameterJdbcTemplate jdbc;

    public FileService(ObjectStorage storage, StorageProperties properties, StoredFileRepository files, PlanLimitService planLimits, NamedParameterJdbcTemplate jdbc) {
        this.storage = storage;
        this.properties = properties;
        this.files = files;
        this.planLimits = planLimits;
        this.jdbc = jdbc;
    }

    // ---- upload ----------------------------------------------------------------------------------------------------------

    /**
     * @throws FileRejectedException with a message for the {@code contentType} or {@code sizeBytes} field
     * @throws com.amanahconnect.plan.PlanLimitExceededException (402) if the file would not fit in the plan's storage
     */
    @Transactional(readOnly = true)
    public Upload prepareUpload(UUID communityId, StoredFileKind kind, String contentType, Long sizeBytes) {
        FilePolicy policy = FilePolicy.of(kind);
        String type = contentType == null ? "" : contentType.trim().toLowerCase();
        String extension = policy.extensionFor(type);
        if (extension == null) throw new FileRejectedException("contentType: must be " + policy.allowedTypesText());
        long max = policy.maxBytes(properties);
        if (sizeBytes == null || sizeBytes < 1 || sizeBytes > max) throw new FileRejectedException("sizeBytes: must be between 1 and " + max + " bytes");
        planLimits.checkStorage(communityId, sizeBytes);
        String key = policy.keyPrefix(communityId) + UUID.randomUUID() + "." + extension;
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, type, sizeBytes);
        return new Upload(upload.url(), "PUT", upload.headers(), key, upload.expiresAt(), max);
    }

    // ---- accept ----------------------------------------------------------------------------------------------------------

    /**
     * Validates an uploaded file and records it (idempotent: accepting the same key again returns the same record).
     *
     * @throws FileRejectedException with a message to show against the field that carried the key
     * @throws com.amanahconnect.plan.PlanLimitExceededException (402) if it does not fit in the plan's storage (the file is deleted)
     */
    public StoredFile accept(UUID communityId, StoredFileKind kind, String key) {
        FilePolicy policy = FilePolicy.of(kind);
        String clean = key == null ? "" : key.trim();
        if (!policy.keyPattern(communityId).matcher(clean).matches()) {
            throw new FileRejectedException("not " + policy.noun() + " uploaded for this community");
        }
        Optional<StoredFile> already = files.findByCommunityIdAndObjectKeyAndDeletedAtIsNull(communityId, clean);
        if (already.isPresent()) return already.get();

        ObjectStorage.ObjectInfo info = storage.head(clean).orElseThrow(() -> new FileRejectedException("the file has not been uploaded"));
        long max = policy.maxBytes(properties);
        if (!policy.allows(info.contentType()) || info.size() > max || info.size() < 1) {
            discard(clean);
            throw new FileRejectedException("the uploaded file is not acceptable (" + policy.friendlyTypesText() + ", at most " + max + " bytes)");
        }
        String extension = clean.substring(clean.lastIndexOf('.') + 1);
        if (!extension.equals(policy.extensionFor(info.contentType()))) {
            discard(clean);
            throw new FileRejectedException("the uploaded file's type does not match its name");
        }
        byte[] head = storage.getRange(clean, 0, MagicBytes.HEAD_BYTES - 1).orElse(new byte[0]);
        if (!MagicBytes.matches(info.contentType(), head)) {
            discard(clean);
            throw new FileRejectedException("the uploaded file's content is not really " + info.contentType());
        }
        lockStorage(communityId);
        try {
            planLimits.checkStorage(communityId, info.size());
        } catch (RuntimeException e) {
            discard(clean);
            throw e;
        }
        return record(communityId, kind, clean, info.contentType(), info.size());
    }

    /** Registers a file the server stored itself (a receipt PDF), replacing the size if the key is already recorded. */
    public StoredFile recordGenerated(UUID communityId, StoredFileKind kind, String key, String contentType, long size) {
        lockStorage(communityId);
        Optional<StoredFile> existing = files.findByCommunityIdAndObjectKeyAndDeletedAtIsNull(communityId, key);
        if (existing.isPresent()) {
            existing.get().setSizeBytes(size);
            existing.get().setContentType(contentType);
            return files.save(existing.get());
        }
        return record(communityId, kind, key, contentType, size);
    }

    // ---- delete, download, usage ------------------------------------------------------------------------------------------

    /** Removes the object and its usage. Never throws for a file that is already gone. */
    public void delete(UUID communityId, String key) {
        if (key == null) return;
        files.findByCommunityIdAndObjectKeyAndDeletedAtIsNull(communityId, key).ifPresent(f -> {
            f.setDeletedAt(Instant.now());
            files.save(f);
        });
        discard(key);
    }

    /** A short-lived signed URL to read the file. */
    public String downloadUrl(String key) {
        return storage.presignDownload(key);
    }

    @Transactional(readOnly = true)
    public long usedBytes(UUID communityId) {
        return files.sumLiveBytesByCommunityId(communityId);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private StoredFile record(UUID communityId, StoredFileKind kind, String key, String contentType, long size) {
        StoredFile file = new StoredFile();
        file.setCommunityId(communityId);
        file.setKind(kind);
        file.setObjectKey(key);
        file.setContentType(contentType);
        file.setSizeBytes(size);
        file.setCreatedBy(AuditService.currentActorId());
        return files.save(file);
    }

    private void discard(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete storage object {}: {}", key, e.toString());
        }
    }

    /** Uploads of one community are accepted one at a time, so two at once cannot both fit into the last megabyte. */
    private void lockStorage(UUID communityId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", new MapSqlParameterSource("key", "storage:" + communityId), rs -> {});
    }
}
