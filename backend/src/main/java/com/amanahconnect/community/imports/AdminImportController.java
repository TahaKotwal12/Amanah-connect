package com.amanahconnect.community.imports;

import com.amanahconnect.audit.AuditHandledBy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/communities/{communityId}/import")
@Tag(name = "Admin · Import", description = "Onboard an existing community from CSV: dry run, then explicit confirm (SUPER_ADMIN, 2FA completed).")
public class AdminImportController {

    /** {@code skipInvalidRows} defaults to false: confirming with invalid rows present is refused. */
    public record ConfirmRequest(Boolean skipInvalidRows) {}

    private final ImportService service;

    public AdminImportController(ImportService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditHandledBy("ImportService records COMMUNITY_IMPORT_DRY_RUN")
    @Operation(
            summary = "Dry-run an import",
            description = "Upload CSV files (parts members, openingBalances, openInvoices; at least one) with a client-chosen batchId. Validates every row and returns the report; changes nothing. 201 for a new batch, 200 when the same batchId and files were already uploaded; 409 when the batchId was used for different files.")
    public ResponseEntity<ImportReport> upload(
            @PathVariable UUID communityId,
            @RequestParam UUID batchId,
            @RequestPart(name = "members", required = false) MultipartFile members,
            @RequestPart(name = "openingBalances", required = false) MultipartFile openingBalances,
            @RequestPart(name = "openInvoices", required = false) MultipartFile openInvoices) {
        ImportService.Outcome outcome = service.dryRun(communityId, batchId, bytes(members), bytes(openingBalances), bytes(openInvoices));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.report());
    }

    @PostMapping("/{batchId}/confirm")
    @AuditHandledBy("ImportService records COMMUNITY_IMPORT_CONFIRMED")
    @Operation(summary = "Confirm a dry-run import", description = "Applies exactly the validated rows in one transaction. Idempotent: confirming again returns the stored result. 402 when the plan's member limit no longer fits; 409 when invalid rows exist (unless skipInvalidRows), when the batch is not confirmable, or when the community changed since the dry run.")
    public ImportReport confirm(@PathVariable UUID communityId, @PathVariable UUID batchId, @RequestBody(required = false) ConfirmRequest body) {
        return service.confirm(communityId, batchId, body != null && Boolean.TRUE.equals(body.skipInvalidRows()));
    }

    @GetMapping("/{batchId}")
    @Operation(summary = "Get an import batch", description = "The report, status (DRY_RUN or CONFIRMED) and, once confirmed, the result.")
    public ImportReport get(@PathVariable UUID communityId, @PathVariable UUID batchId) {
        return service.get(communityId, batchId);
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || (file.isEmpty() && (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()))) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
