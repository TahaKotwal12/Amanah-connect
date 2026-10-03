package com.amanahconnect.member;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.community.imports.ImportReport;
import com.amanahconnect.community.imports.ImportService;
import com.amanahconnect.tenant.CurrentCommunity;
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

/** A community admin importing their own members from CSV: a dry run, then an explicit confirm. */
@RestController
@RequestMapping("/api/v1/community/members/import")
@Tag(name = "Community · Members", description = "The community's members (COMMUNITY_ADMIN). Members have no login.")
public class MemberImportController {

    /** {@code skipInvalidRows} defaults to false: confirming while invalid rows exist is refused. */
    public record ConfirmRequest(Boolean skipInvalidRows) {}

    private final ImportService service;

    public MemberImportController(ImportService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditHandledBy("ImportService records COMMUNITY_IMPORT_DRY_RUN")
    @Operation(
            summary = "Dry-run a members CSV import",
            description = "Upload a CSV (part `file`, UTF-8, at most 5000 rows) with a client-chosen batchId. Columns: full_name (required), member_no (optional: generated when blank), email, phone, group, status, joined_on, consent_email. Validates every row and reports reasons; changes nothing. 201 for a new batch, 200 for a repeat of the same batchId and file, 409 if the batchId was used for another file.")
    public ResponseEntity<ImportReport> upload(
            @CurrentCommunity UUID communityId, @RequestParam UUID batchId, @RequestPart(name = "file") MultipartFile file) {
        ImportService.Outcome outcome = service.dryRun(communityId, batchId, bytes(file), null, null, true);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.report());
    }

    @PostMapping("/{batchId}/confirm")
    @AuditHandledBy("ImportService records COMMUNITY_IMPORT_CONFIRMED")
    @Operation(summary = "Confirm a dry-run import", description = "Applies exactly the validated rows in one transaction, after re-checking the plan's member limit (402) and the community's data (409). Idempotent: confirming again returns the stored result. No welcome emails are sent for imported members.")
    public ImportReport confirm(@CurrentCommunity UUID communityId, @PathVariable UUID batchId, @RequestBody(required = false) ConfirmRequest body) {
        return service.confirm(communityId, batchId, body != null && Boolean.TRUE.equals(body.skipInvalidRows()));
    }

    @GetMapping("/{batchId}")
    @Operation(summary = "Get an import batch", description = "The report, status (DRY_RUN or CONFIRMED) and, once confirmed, the result.")
    public ImportReport get(@CurrentCommunity UUID communityId, @PathVariable UUID batchId) {
        return service.get(communityId, batchId);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
