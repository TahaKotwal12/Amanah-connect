package com.amanahconnect.report;

import com.amanahconnect.report.ReportService.Format;
import com.amanahconnect.report.ReportService.Kind;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/reports")
@Tag(name = "Community · Reports", description = "Finance reports as CSV or PDF (COMMUNITY_ADMIN). CSV needs the csv_export plan feature, PDF the pdf_reports feature.")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/{report}")
    @Operation(
            summary = "Download a report",
            description = "report is one of: income-expense, category-breakdown, collection, member-dues (needs memberId), defaulters (optional asOf), receipts-register. "
                    + "format is csv (default) or pdf. from and to limit the period where it applies. Every export is audited.")
    public ResponseEntity<byte[]> report(
            @CurrentCommunity UUID communityId,
            @PathVariable String report,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Format parsed = switch (format.toLowerCase()) {
            case "csv" -> Format.CSV;
            case "pdf" -> Format.PDF;
            default -> throw new com.amanahconnect.common.error.ApiException(com.amanahconnect.common.error.ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", java.util.List.of("format: must be csv or pdf"));
        };
        ReportService.Output output = service.export(communityId, Kind.of(report), parsed, from, to, memberId, asOf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(output.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(output.filename()).build().toString()).body(output.bytes());
    }
}
