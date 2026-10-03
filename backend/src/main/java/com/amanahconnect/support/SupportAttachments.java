package com.amanahconnect.support;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.file.StorageProperties;
import com.amanahconnect.support.SupportDtos.AttachmentUploadRequest;
import com.amanahconnect.support.SupportDtos.AttachmentUploadView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Chat attachments: the browser uploads straight to S3 with a signed URL for exactly the declared type and size; a message
 * may then only reference a key the server issued for THIS community, that really is in storage, with a type and size on
 * the allow-list. A key can be attached to one message only.
 */
@Component
public class SupportAttachments {

    private static final Logger log = LoggerFactory.getLogger(SupportAttachments.class);
    private static final Map<String, String> TYPES = Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "application/pdf", "pdf");
    private static final Pattern KEY = Pattern.compile("^communities/[0-9a-f-]{36}/support/[0-9a-f-]{36}\\.(png|jpg|webp|pdf)$");

    public record Accepted(String key, String name, String contentType, long size) {}

    private final ObjectStorage storage;
    private final StorageProperties properties;
    private final SupportMessageRepository messages;

    public SupportAttachments(ObjectStorage storage, StorageProperties properties, SupportMessageRepository messages) {
        this.storage = storage;
        this.properties = properties;
        this.messages = messages;
    }

    public AttachmentUploadView uploadUrl(UUID communityId, AttachmentUploadRequest request) {
        String type = request.contentType().trim().toLowerCase();
        String extension = TYPES.get(type);
        if (extension == null) throw invalid("contentType", "must be image/png, image/jpeg, image/webp or application/pdf");
        long max = properties.attachmentMaxBytes();
        if (request.sizeBytes() == null || request.sizeBytes() < 1 || request.sizeBytes() > max) throw invalid("sizeBytes", "must be between 1 and " + max + " bytes");
        String key = prefix(communityId) + UUID.randomUUID() + "." + extension;
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, type, request.sizeBytes());
        return new AttachmentUploadView(upload.url(), "PUT", upload.headers(), key, upload.expiresAt(), max);
    }

    /** Checks the key and what is stored under it; returns what to record on the message, or null when there is no attachment. */
    public Accepted accept(UUID communityId, String key, String name) {
        if (key == null || key.isBlank()) return null;
        String trimmed = key.trim();
        if (!trimmed.startsWith(prefix(communityId)) || !KEY.matcher(trimmed).matches()) throw invalid("attachmentKey", "not an attachment uploaded for this community");
        if (messages.existsByCommunityIdAndAttachmentKey(communityId, trimmed)) throw invalid("attachmentKey", "already used by another message");
        var info = storage.head(trimmed);
        if (info.isEmpty()) throw invalid("attachmentKey", "the file has not been uploaded");
        if (!TYPES.containsKey(info.get().contentType()) || info.get().size() > properties.attachmentMaxBytes() || info.get().size() < 1) {
            try {
                storage.delete(trimmed);
            } catch (RuntimeException e) {
                log.warn("Could not delete a rejected chat attachment: {}", e.toString());
            }
            throw invalid("attachmentKey", "the uploaded file is not acceptable (PNG, JPEG, WebP or PDF, at most " + properties.attachmentMaxBytes() + " bytes)");
        }
        String extension = trimmed.substring(trimmed.lastIndexOf('.') + 1);
        String cleanName = name == null ? "" : com.amanahconnect.common.Text.singleLine(name).replaceAll("[\\\\/]", "_");
        if (cleanName.isBlank()) cleanName = "attachment." + extension;
        if (cleanName.length() > 200) cleanName = cleanName.substring(0, 200);
        return new Accepted(trimmed, cleanName, info.get().contentType(), info.get().size());
    }

    private static String prefix(UUID communityId) {
        return "communities/" + communityId + "/support/";
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}
