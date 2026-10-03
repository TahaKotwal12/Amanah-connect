package com.amanahconnect.support;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.file.FileRejectedException;
import com.amanahconnect.file.FileService;
import com.amanahconnect.file.StoredFile;
import com.amanahconnect.file.StoredFileKind;
import com.amanahconnect.support.SupportDtos.AttachmentUploadRequest;
import com.amanahconnect.support.SupportDtos.AttachmentUploadView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Chat attachments: the browser uploads straight to S3 with a signed URL for exactly the declared type and size; a message
 * may then only reference a key the server issued for THIS community, that really is in storage, with a type and size on
 * the allow-list. A key can be attached to one message only.
 */
@Component
public class SupportAttachments {


    public record Accepted(String key, String name, String contentType, long size) {}

    private final FileService files;
    private final SupportMessageRepository messages;

    public SupportAttachments(FileService files, SupportMessageRepository messages) {
        this.files = files;
        this.messages = messages;
    }

    public AttachmentUploadView uploadUrl(UUID communityId, AttachmentUploadRequest request) {
        FileService.Upload upload;
        try {
            upload = files.prepareUpload(communityId, StoredFileKind.SUPPORT_ATTACHMENT, request.contentType(), request.sizeBytes());
        } catch (FileRejectedException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(e.getMessage()));
        }
        return new AttachmentUploadView(upload.uploadUrl(), upload.method(), upload.headers(), upload.key(), upload.expiresAt(), upload.maxBytes());
    }

    /** Checks the key and what is stored under it; returns what to record on the message, or null when there is no attachment. */
    public Accepted accept(UUID communityId, String key, String name) {
        if (key == null || key.isBlank()) return null;
        String trimmed = key.trim();
        if (messages.existsByCommunityIdAndAttachmentKey(communityId, trimmed)) throw invalid("attachmentKey", "already used by another message");
        StoredFile file;
        try {
            file = files.accept(communityId, StoredFileKind.SUPPORT_ATTACHMENT, trimmed);
        } catch (FileRejectedException e) {
            throw invalid("attachmentKey", e.getMessage());
        }
        String extension = trimmed.substring(trimmed.lastIndexOf('.') + 1);
        String cleanName = name == null ? "" : com.amanahconnect.common.Text.singleLine(name).replaceAll("[\\\\/]", "_");
        if (cleanName.isBlank()) cleanName = "attachment." + extension;
        if (cleanName.length() > 200) cleanName = cleanName.substring(0, 200);
        return new Accepted(trimmed, cleanName, file.getContentType(), file.getSizeBytes());
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}
