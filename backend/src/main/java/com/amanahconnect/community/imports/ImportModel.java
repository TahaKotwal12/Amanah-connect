package com.amanahconnect.community.imports;

import java.util.List;
import java.util.UUID;

/** The validated rows a dry run stores and a confirm applies. Amounts are plain decimal strings, dates ISO strings. */
public final class ImportModel {

    private ImportModel() {}

    public record MemberRow(int row, String memberNo, String fullName, String email, String phone, String group, String status, String joinedOn, boolean consentEmail) {}

    public record BalanceRow(int row, UUID categoryId, String type, String categoryName, String amount, String asOf, String title) {}

    public record InvoiceRow(int row, String memberNo, String invoiceNo, String kind, String period, String amount, String dueDate, String status) {}

    public record StoredRows(List<MemberRow> members, List<BalanceRow> openingBalances, List<InvoiceRow> openInvoices) {
        public static StoredRows empty() {
            return new StoredRows(List.of(), List.of(), List.of());
        }
    }
}
