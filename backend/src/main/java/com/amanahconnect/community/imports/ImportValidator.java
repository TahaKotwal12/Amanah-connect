package com.amanahconnect.community.imports;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.imports.ImportModel.BalanceRow;
import com.amanahconnect.community.imports.ImportModel.InvoiceRow;
import com.amanahconnect.community.imports.ImportModel.MemberRow;
import com.amanahconnect.community.imports.ImportReport.FileReport;
import com.amanahconnect.community.imports.ImportReport.RowResult;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Validates parsed CSV rows one by one and cross-checks them against the community's current data. Pure
 * validation: it writes nothing. Every message says what is wrong with that row in plain words.
 */
@Component
public class ImportValidator {

    static final Set<String> MEMBER_COLUMNS = Set.of("member_no", "full_name", "email", "phone", "group", "status", "joined_on", "consent_email");
    static final Set<String> BALANCE_COLUMNS = Set.of("category", "type", "amount", "as_of", "description");
    static final Set<String> INVOICE_COLUMNS = Set.of("member_no", "invoice_no", "kind", "period", "amount", "due_date");

    private static final Pattern KEY = Pattern.compile("^[\\w./\\-]+$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\-. ]{5,30}$");
    /** Numbers the platform itself issues ("INV-2025-26/000001"): a legacy number in that shape could collide later. */
    private static final Pattern PLATFORM_NUMBER = Pattern.compile("^(INV|RCP)-\\d{4}-\\d{2}/\\d+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\p{Cf}&&[^\\t]]");
    private static final List<DateTimeFormatter> DATES = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT));
    private static final Set<String> KINDS = Set.of("MEMBERSHIP", "DONATION", "EVENT", "OTHER");
    private static final Set<String> TRUE = Set.of("true", "yes", "y", "1");
    private static final Set<String> FALSE = Set.of("false", "no", "n", "0");

    private final NamedParameterJdbcTemplate jdbc;

    public ImportValidator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What a file produced: the report section plus the rows that passed. */
    public record Validated<T>(FileReport report, List<T> rows) {}

    // ---- members ------------------------------------------------------------------------------------

    public Validated<MemberRow> members(UUID communityId, CsvTable table, LocalDate today) {
        List<String> fileErrors = headerErrors(table, Set.of("member_no", "full_name"), MEMBER_COLUMNS);
        List<String> warnings = unknownColumns(table, MEMBER_COLUMNS);
        if (!fileErrors.isEmpty()) {
            return rejected(table, fileErrors, warnings);
        }
        Set<String> existing = existingMemberNos(communityId, table.rows().stream().map(r -> r.get("member_no")).filter(s -> !s.isEmpty()).toList(), false);
        Map<String, Integer> seen = new HashMap<>();
        List<MemberRow> valid = new ArrayList<>();
        List<RowResult> validRefs = new ArrayList<>();
        List<RowResult> invalid = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            List<String> errors = new ArrayList<>();
            columnCount(row, table, errors);
            controlChars(row, errors);
            String memberNo = row.get("member_no");
            if (memberNo.isEmpty()) {
                errors.add("member_no is required");
            } else if (memberNo.length() > 30) {
                errors.add("member_no is longer than 30 characters");
            } else if (!KEY.matcher(memberNo).matches()) {
                errors.add("member_no may only contain letters, digits, '.', '/', '-' and '_'");
            } else if (existing.contains(memberNo)) {
                errors.add("member_no " + memberNo + " already exists in this community");
            } else if (seen.containsKey(memberNo)) {
                errors.add("member_no " + memberNo + " is repeated (first used on row " + seen.get(memberNo) + ")");
            }
            String fullName = row.get("full_name");
            if (fullName.isEmpty()) errors.add("full_name is required");
            else if (fullName.length() > 150) errors.add("full_name is longer than 150 characters");
            String email = row.get("email");
            if (!email.isEmpty() && (email.length() > 254 || !EMAIL.matcher(email).matches())) errors.add("email is not a valid address");
            String phone = row.get("phone");
            if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) errors.add("phone is not a valid phone number");
            String group = row.get("group");
            if (group.length() > 100) errors.add("group is longer than 100 characters");
            String status = row.get("status").isEmpty() ? "ACTIVE" : row.get("status").toUpperCase(Locale.ROOT);
            if (!status.equals("ACTIVE") && !status.equals("INACTIVE")) errors.add("status must be ACTIVE or INACTIVE");
            LocalDate joinedOn = null;
            if (!row.get("joined_on").isEmpty()) {
                joinedOn = date(row.get("joined_on"));
                if (joinedOn == null) errors.add("joined_on is not a date (use yyyy-MM-dd, dd/MM/yyyy or dd-MM-yyyy)");
                else if (joinedOn.isAfter(today)) errors.add("joined_on is in the future");
            }
            Boolean consent = bool(row.get("consent_email"));
            if (consent == null) errors.add("consent_email must be yes/no or true/false");

            if (errors.isEmpty()) {
                seen.put(memberNo, row.number());
                valid.add(new MemberRow(row.number(), memberNo, fullName, email.isEmpty() ? null : email, phone.isEmpty() ? null : phone,
                        group.isEmpty() ? null : group, status, joinedOn == null ? null : joinedOn.toString(), Boolean.TRUE.equals(consent)));
                validRefs.add(new RowResult(row.number(), memberNo, List.of()));
            } else {
                if (!memberNo.isEmpty()) seen.putIfAbsent(memberNo, row.number());
                invalid.add(new RowResult(row.number(), memberNo, errors));
            }
        }
        return new Validated<>(file(table, validRefs, invalid, List.of(), warnings), valid);
    }

    // ---- opening balances ------------------------------------------------------------------------------

    public Validated<BalanceRow> openingBalances(UUID communityId, CsvTable table, LocalDate today) {
        List<String> fileErrors = headerErrors(table, Set.of("category", "type", "amount", "as_of"), BALANCE_COLUMNS);
        List<String> warnings = unknownColumns(table, BALANCE_COLUMNS);
        if (!fileErrors.isEmpty()) {
            return rejected(table, fileErrors, warnings);
        }
        Map<String, UUID> categories = new HashMap<>();
        jdbc.query("SELECT id, lower(name) AS name, type FROM ledger_categories WHERE community_id = :c AND active",
                new MapSqlParameterSource("c", communityId),
                rs -> { categories.put(rs.getString("type") + "|" + rs.getString("name"), rs.getObject("id", UUID.class)); });
        List<BalanceRow> valid = new ArrayList<>();
        List<RowResult> validRefs = new ArrayList<>();
        List<RowResult> invalid = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            List<String> errors = new ArrayList<>();
            columnCount(row, table, errors);
            controlChars(row, errors);
            String category = row.get("category");
            String type = row.get("type").toUpperCase(Locale.ROOT);
            UUID categoryId = null;
            if (category.isEmpty()) errors.add("category is required");
            if (!type.equals("INCOME") && !type.equals("EXPENSE")) {
                errors.add("type must be INCOME or EXPENSE");
            } else if (!category.isEmpty()) {
                categoryId = categories.get(type + "|" + category.toLowerCase(Locale.ROOT));
                if (categoryId == null) errors.add("this community has no active " + type + " category named '" + category + "'");
            }
            Money amount = money(row.get("amount"), errors, "amount");
            LocalDate asOf = date(row.get("as_of"));
            if (row.get("as_of").isEmpty()) errors.add("as_of is required");
            else if (asOf == null) errors.add("as_of is not a date (use yyyy-MM-dd, dd/MM/yyyy or dd-MM-yyyy)");
            else if (asOf.isAfter(today)) errors.add("as_of is in the future");
            String description = row.get("description");
            if (description.length() > 200) errors.add("description is longer than 200 characters");

            if (errors.isEmpty()) {
                String title = description.isEmpty() ? "Opening balance - " + category : description;
                valid.add(new BalanceRow(row.number(), categoryId, type, category, amount.toString(), asOf.toString(), title));
                validRefs.add(new RowResult(row.number(), category, List.of()));
            } else {
                invalid.add(new RowResult(row.number(), category, errors));
            }
        }
        return new Validated<>(file(table, validRefs, invalid, List.of(), warnings), valid);
    }

    // ---- open invoices ------------------------------------------------------------------------------------

    /**
     * @param batchMembers members from the same upload that passed validation (an invoice may refer to them)
     * @param failedMembers member_no values from the upload that failed, with their row, to explain a missing reference
     */
    public Validated<InvoiceRow> openInvoices(UUID communityId, CsvTable table, LocalDate today, Set<String> batchMembers, Map<String, Integer> failedMembers) {
        List<String> fileErrors = headerErrors(table, Set.of("member_no", "invoice_no", "amount", "due_date"), INVOICE_COLUMNS);
        List<String> warnings = unknownColumns(table, INVOICE_COLUMNS);
        if (!fileErrors.isEmpty()) {
            return rejected(table, fileErrors, warnings);
        }
        Set<String> inDb = existingMemberNos(communityId, table.rows().stream().map(r -> r.get("member_no")).filter(s -> !s.isEmpty()).toList(), true);
        Set<String> existingInvoices = existingInvoiceNos(communityId, table.rows().stream().map(r -> r.get("invoice_no")).filter(s -> !s.isEmpty()).toList());
        Map<String, Integer> seen = new HashMap<>();
        List<InvoiceRow> valid = new ArrayList<>();
        List<RowResult> validRefs = new ArrayList<>();
        List<RowResult> invalid = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            List<String> errors = new ArrayList<>();
            columnCount(row, table, errors);
            controlChars(row, errors);
            String memberNo = row.get("member_no");
            if (memberNo.isEmpty()) {
                errors.add("member_no is required");
            } else if (!inDb.contains(memberNo) && !batchMembers.contains(memberNo)) {
                errors.add(failedMembers.containsKey(memberNo)
                        ? "member_no " + memberNo + " is a member on row " + failedMembers.get(memberNo) + " of the members file, which failed validation"
                        : "member_no " + memberNo + " is not a member of this community or of this upload");
            }
            String invoiceNo = row.get("invoice_no");
            if (invoiceNo.isEmpty()) {
                errors.add("invoice_no is required");
            } else if (invoiceNo.length() > 40) {
                errors.add("invoice_no is longer than 40 characters");
            } else if (!KEY.matcher(invoiceNo).matches()) {
                errors.add("invoice_no may only contain letters, digits, '.', '/', '-' and '_'");
            } else if (PLATFORM_NUMBER.matcher(invoiceNo).matches()) {
                errors.add("invoice_no looks like a number this system issues itself; use your old system's number or add a prefix such as OLD-");
            } else if (existingInvoices.contains(invoiceNo)) {
                errors.add("invoice_no " + invoiceNo + " already exists in this community");
            } else if (seen.containsKey(invoiceNo)) {
                errors.add("invoice_no " + invoiceNo + " is repeated (first used on row " + seen.get(invoiceNo) + ")");
            }
            String kind = row.get("kind").isEmpty() ? "MEMBERSHIP" : row.get("kind").toUpperCase(Locale.ROOT);
            if (!KINDS.contains(kind)) errors.add("kind must be MEMBERSHIP, DONATION, EVENT or OTHER");
            String period = row.get("period");
            if (period.length() > 30) errors.add("period is longer than 30 characters");
            Money amount = money(row.get("amount"), errors, "amount");
            LocalDate due = date(row.get("due_date"));
            if (row.get("due_date").isEmpty()) errors.add("due_date is required");
            else if (due == null) errors.add("due_date is not a date (use yyyy-MM-dd, dd/MM/yyyy or dd-MM-yyyy)");

            if (errors.isEmpty()) {
                seen.put(invoiceNo, row.number());
                valid.add(new InvoiceRow(row.number(), memberNo, invoiceNo, kind, period.isEmpty() ? null : period, amount.toString(), due.toString(),
                        due.isBefore(today) ? "OVERDUE" : "ISSUED"));
                validRefs.add(new RowResult(row.number(), invoiceNo, List.of()));
            } else {
                if (!invoiceNo.isEmpty()) seen.putIfAbsent(invoiceNo, row.number());
                invalid.add(new RowResult(row.number(), invoiceNo, errors));
            }
        }
        return new Validated<>(file(table, validRefs, invalid, List.of(), warnings), valid);
    }

    // ---- database lookups, shared with the confirm re-check ------------------------------------------------

    public Set<String> existingMemberNos(UUID communityId, List<String> memberNos, boolean notDeletedOnly) {
        if (memberNos.isEmpty()) return Set.of();
        Set<String> found = new HashSet<>();
        jdbc.query(
                "SELECT member_no FROM members WHERE community_id = :c AND member_no IN (:nos)" + (notDeletedOnly ? " AND deleted_at IS NULL" : ""),
                new MapSqlParameterSource("c", communityId).addValue("nos", new LinkedHashSet<>(memberNos)),
                rs -> { found.add(rs.getString(1)); });
        return found;
    }

    public Set<String> existingInvoiceNos(UUID communityId, List<String> invoiceNos) {
        if (invoiceNos.isEmpty()) return Set.of();
        Set<String> found = new HashSet<>();
        jdbc.query("SELECT invoice_no FROM invoices WHERE community_id = :c AND invoice_no IN (:nos)",
                new MapSqlParameterSource("c", communityId).addValue("nos", new LinkedHashSet<>(invoiceNos)),
                rs -> { found.add(rs.getString(1)); });
        return found;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static List<String> headerErrors(CsvTable table, Set<String> required, Set<String> known) {
        List<String> errors = new ArrayList<>();
        for (String column : required) {
            if (!table.headers().contains(column)) errors.add("Missing required column '" + column + "'. Columns found: " + String.join(", ", table.headers()));
        }
        Set<String> seen = new HashSet<>();
        for (String header : table.headers()) {
            if (!header.isEmpty() && !seen.add(header)) errors.add("Column '" + header + "' appears more than once");
        }
        return errors;
    }

    private static List<String> unknownColumns(CsvTable table, Set<String> known) {
        List<String> warnings = new ArrayList<>();
        for (String header : table.headers()) {
            if (!known.contains(header)) {
                warnings.add(header.isEmpty() ? "A column without a name is ignored" : "Column '" + header + "' is not recognised and is ignored");
            }
        }
        return warnings;
    }

    private static <T> Validated<T> rejected(CsvTable table, List<String> errors, List<String> warnings) {
        return new Validated<>(new FileReport(true, table.rows().size(), 0, table.rows().size(), errors, warnings, List.of(), List.of()), List.of());
    }

    private static FileReport file(CsvTable table, List<RowResult> valid, List<RowResult> invalid, List<String> errors, List<String> warnings) {
        return new FileReport(true, table.rows().size(), valid.size(), invalid.size(), errors, warnings, valid, invalid);
    }

    private static void columnCount(CsvTable.Row row, CsvTable table, List<String> errors) {
        if (row.cellCount() > table.headers().size()) {
            errors.add("has " + row.cellCount() + " values but the header has " + table.headers().size() + " columns");
        }
    }

    private static void controlChars(CsvTable.Row row, List<String> errors) {
        for (Map.Entry<String, String> cell : row.cells().entrySet()) {
            if (cell.getValue() != null && CONTROL.matcher(cell.getValue()).find()) {
                errors.add(cell.getKey() + " contains control characters");
            }
        }
    }

    static LocalDate date(String text) {
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // try the next accepted format
            }
        }
        return null;
    }

    private static Boolean bool(String text) {
        if (text.isEmpty()) return Boolean.FALSE;
        String lower = text.toLowerCase(Locale.ROOT);
        if (TRUE.contains(lower)) return Boolean.TRUE;
        if (FALSE.contains(lower)) return Boolean.FALSE;
        return null;
    }

    private static Money money(String text, List<String> errors, String field) {
        if (text.isEmpty()) {
            errors.add(field + " is required");
            return null;
        }
        try {
            Money money = Money.parse(text);
            if (!money.isPositive()) {
                errors.add(field + " must be greater than zero");
                return null;
            }
            return money;
        } catch (IllegalArgumentException e) {
            errors.add(field + " is not a valid amount (use e.g. 1250.50: digits only, at most 2 decimals, no currency symbol or commas)");
            return null;
        }
    }
}
