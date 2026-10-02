package com.amanahconnect.community.imports;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What a dry run (and later a confirm) reports back. */
public record ImportReport(
        UUID batchId,
        String status,
        boolean confirmable,
        Summary summary,
        FileReport members,
        FileReport openingBalances,
        FileReport openInvoices,
        List<String> blockingErrors,
        List<String> warnings,
        Map<String, Object> result) {

    public record Summary(int validRows, int invalidRows, int newMembers, Long planMemberLimit, long currentMembers) {}

    /** A row reference: its spreadsheet row number, its key (member_no / invoice_no / category) and, if invalid, why. */
    public record RowResult(int row, String key, List<String> errors) {}

    /** {@code provided} is false when that file was not uploaded. */
    public record FileReport(boolean provided, int totalRows, int validRows, int invalidRows, List<String> errors, List<String> warnings, List<RowResult> valid, List<RowResult> invalid) {
        public static FileReport notProvided() {
            return new FileReport(false, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
        }
    }

    public ImportReport with(String newStatus, Map<String, Object> newResult) {
        return new ImportReport(batchId, newStatus, confirmable, summary, members, openingBalances, openInvoices, blockingErrors, warnings, newResult);
    }
}
