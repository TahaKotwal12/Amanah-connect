package com.amanahconnect.dataexport;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.dataexport.DataExportDtos.DownloadUrl;
import com.amanahconnect.dataexport.DataExportDtos.ExportView;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/data-exports")
@Tag(name = "Community · Data export", description = "Take all of the community's data out as a ZIP of CSV files (COMMUNITY_ADMIN).")
public class DataExportController {

    private final DataExportService service;

    public DataExportController(DataExportService service) {
        this.service = service;
    }

    @PostMapping
    @AuditHandledBy("DataExportService records DATA_EXPORT_REQUESTED; the worker records COMPLETED or FAILED")
    @Operation(summary = "Ask for a download of all the community's data",
            description = "Answers 202 at once. A background job writes one CSV per table (members, invoices, payments, receipts, ledger, complaints, announcements, settings, the audit trail and more) into a ZIP, "
                    + "and emails you a link that works for 48 hours. One at a time (409 while one is being prepared); up to 3 per 24 hours (429).")
    public ResponseEntity<ExportView> request(@CurrentCommunity UUID communityId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.request(communityId));
    }

    @GetMapping
    @Operation(summary = "Your latest data exports", description = "Newest first, up to 20, with their state: PENDING, RUNNING, READY, FAILED or EXPIRED.")
    public List<ExportView> list(@CurrentCommunity UUID communityId) {
        return service.list(communityId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One export", description = "When READY it shows the size, the rows per table and when the link expires.")
    public ExportView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(communityId, id);
    }

    @GetMapping("/{id}/download-url")
    @AuditHandledBy("DataExportService records DATA_EXPORT_DOWNLOADED")
    @Operation(summary = "A fresh download address for a ready export", description = "Valid for 5 minutes; ask again for another. Only while the export is READY (before its link expires). Recorded in the audit trail.")
    public DownloadUrl downloadUrl(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.downloadUrl(communityId, id);
    }
}
