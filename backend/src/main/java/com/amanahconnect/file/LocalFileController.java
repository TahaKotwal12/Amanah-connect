package com.amanahconnect.file;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.audit.AuditHandledBy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The upload and download ends of local-disk storage (only active when {@code app.storage.local-path} is set; otherwise every
 * request here is a plain 404). There is no login on these URLs: the signature in the link is the permission, it names exactly one
 * file and one action, and it expires. See {@link LocalDiskObjectStorage}.
 */
@RestController
@RequestMapping("/api/v1/public/files")
@Tag(name = "Public · Files", description = "Signed upload and download links for files kept on this server's disk. Only used when local-disk storage is configured.")
class LocalFileController {

    private final ObjectStorage storage;

    LocalFileController(ObjectStorage storage) {
        this.storage = storage;
    }

    private LocalDiskObjectStorage local() {
        if (storage instanceof LocalDiskObjectStorage disk) {
            return disk;
        }
        throw new ApiException(ErrorCode.NOT_FOUND, "Not found");
    }

    @PutMapping("/upload")
    @AuditHandledBy("storing bytes under a signed link changes no record; the record that uses the file (an attachment, a logo) is audited when it is saved")
    @Operation(summary = "Upload a file to a signed link", description = "The body is the file. Its size and Content-Type must be exactly what the link was signed for.")
    ResponseEntity<Void> upload(
            @RequestParam String key,
            @RequestParam long exp,
            @RequestParam long size,
            @RequestParam String type,
            @RequestParam String sig,
            HttpServletRequest request)
            throws IOException {
        LocalDiskObjectStorage disk = local();
        if (!disk.verify("upload", key, exp, size, type, sig)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This upload link is not valid or has expired.");
        }
        String sentType = request.getContentType() == null ? "" : request.getContentType().split(";")[0].trim();
        if (!sentType.equalsIgnoreCase(type) || request.getContentLengthLong() != size) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "The file does not match what the link allows.");
        }
        // Read exactly `size` bytes and no more: a body longer than the signed size is refused, not truncated.
        try (InputStream in = new BoundedInputStream(request.getInputStream(), size)) {
            disk.store(key, in, type);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/download")
    @Operation(summary = "Download a file from a signed link")
    ResponseEntity<FileSystemResource> download(@RequestParam String key, @RequestParam long exp, @RequestParam String sig) {
        LocalDiskObjectStorage disk = local();
        if (!disk.verify("download", key, exp, 0, "", sig)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Not found");
        }
        Path file = disk.file(key).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Not found"));
        String type = disk.contentType(file);
        boolean inline = type.startsWith("image/") || type.equals("application/pdf");
        String name = file.getFileName().toString();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(name).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(file));
    }

    /** Stops reading after {@code limit} bytes and fails if the stream has more, so an upload can never exceed the size it was signed for. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return overrun();
            }
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return overrun();
            }
            int n = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        private int overrun() throws IOException {
            if (in.read() >= 0) {
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The file is larger than the link allows.");
            }
            return -1;
        }
    }
}
