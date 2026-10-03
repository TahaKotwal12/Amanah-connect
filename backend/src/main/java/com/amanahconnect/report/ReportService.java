package com.amanahconnect.report;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.ledger.LedgerDtos.CategoryTotal;
import com.amanahconnect.ledger.LedgerDtos.SummaryView;
import com.amanahconnect.ledger.LedgerQueries;
import com.amanahconnect.ledger.LedgerType;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.plan.PlanLimitKeys;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.report.ReportData.Column;
import com.amanahconnect.report.ReportData.Row;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The finance reports. Each is computed with aggregate SQL scoped to the community, becomes a {@link ReportData}, and is
 * written as CSV (plan feature {@code csv_export}) or PDF (plan feature {@code pdf_reports}). Every export is audited.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** A guard against exports nobody can open; narrow the dates instead. */
    static final int MAX_ROWS = 20_000;

    public enum Format { CSV, PDF }

    public record Output(byte[] bytes, String contentType, String filename, int rows) {}

    /** The reports, by their URL name. */
    public enum Kind {
        INCOME_EXPENSE("income-expense"), CATEGORY_BREAKDOWN("category-breakdown"), COLLECTION("collection"),
        MEMBER_DUES("member-dues"), DEFAULTERS("defaulters"), RECEIPTS_REGISTER("receipts-register");

        final String slug;

        Kind(String slug) {
            this.slug = slug;
        }

        public static Kind of(String slug) {
            for (Kind kind : values()) if (kind.slug.equals(slug)) return kind;
            throw new NotFoundException();
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final LedgerQueries ledger;
    private final CommunityRepository communities;
    private final MemberRepository members;
    private final PlanLimitService planLimits;
    private final ReportWriters writers;
    private final AuditService audit;
    private final Clock clock;

    public ReportService(
            NamedParameterJdbcTemplate jdbc,
            LedgerQueries ledger,
            CommunityRepository communities,
            MemberRepository members,
            PlanLimitService planLimits,
            ReportWriters writers,
            AuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.communities = communities;
        this.members = members;
        this.planLimits = planLimits;
        this.writers = writers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional // read-write: the export is audited
    public Output export(UUID communityId, Kind kind, Format format, LocalDate from, LocalDate to, UUID memberId, LocalDate asOf) {
        planLimits.requireFeature(communityId, format == Format.PDF ? PlanLimitKeys.FEATURE_PDF_REPORTS : PlanLimitKeys.FEATURE_CSV_EXPORT);
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("to: cannot be before from"));
        }
        Community community = communities.findById(communityId).orElseThrow(NotFoundException::new);
        LocalDate today = LocalDate.now(clock.withZone(IST));
        ReportData data = switch (kind) {
            case INCOME_EXPENSE -> incomeExpense(community, from, to);
            case CATEGORY_BREAKDOWN -> categoryBreakdown(community, from, to);
            case COLLECTION -> collection(community, from, to);
            case MEMBER_DUES -> memberDues(community, memberId);
            case DEFAULTERS -> defaulters(community, asOf == null ? today : asOf);
            case RECEIPTS_REGISTER -> receiptsRegister(community, from, to);
        };
        if (data.rows().size() > MAX_ROWS) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "This report has more than " + MAX_ROWS + " rows. Narrow the date range.");
        }
        byte[] bytes = format == Format.PDF ? writers.pdf(data, community.getName()) : writers.csv(data);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("report", kind.slug);
        after.put("format", format.name());
        after.put("rows", data.rows().size());
        after.put("from", from == null ? null : from.toString());
        after.put("to", to == null ? null : to.toString());
        audit.record("REPORT_EXPORTED", "Report", null, null, after);
        String name = kind.slug + "-" + today + (format == Format.PDF ? ".pdf" : ".csv");
        return new Output(bytes, format == Format.PDF ? "application/pdf" : "text/csv;charset=UTF-8", name, data.rows().size());
    }

    // ---- ledger reports ---------------------------------------------------------------------------------------------------

    private ReportData incomeExpense(Community community, LocalDate from, LocalDate to) {
        SummaryView summary = ledger.summary(community.getId(), from, to, community.getOpeningBalance());
        List<Row> rows = new ArrayList<>();
        rows.add(Row.bold("INCOME", "", null));
        for (CategoryTotal c : summary.byCategory()) if (c.type() == LedgerType.INCOME) rows.add(Row.of("", c.name(), c.total().amount()));
        rows.add(Row.bold("", "Total income", summary.totalIncome().amount()));
        rows.add(Row.bold("EXPENSE", "", null));
        for (CategoryTotal c : summary.byCategory()) if (c.type() == LedgerType.EXPENSE) rows.add(Row.of("", c.name(), c.total().amount()));
        rows.add(Row.bold("", "Total expense", summary.totalExpense().amount()));
        List<String> context = new ArrayList<>(period(from, to));
        context.add("Opening balance " + community.getCurrency() + " " + summary.openingBalance() + "; closing balance " + community.getCurrency() + " " + summary.closingBalance());
        return new ReportData("income-expense", "Income and expense statement", context, List.of(Column.text("Section"), Column.text("Category"), Column.amount("Amount")), rows,
                Row.bold("", "Net surplus / (deficit)", summary.net().amount()), community.getCurrency());
    }

    private ReportData categoryBreakdown(Community community, LocalDate from, LocalDate to) {
        SummaryView summary = ledger.summary(community.getId(), from, to, community.getOpeningBalance());
        List<Row> rows = new ArrayList<>();
        for (LedgerType type : LedgerType.values()) {
            BigDecimal typeTotal = type == LedgerType.INCOME ? summary.totalIncome().amount() : summary.totalExpense().amount();
            for (CategoryTotal c : summary.byCategory()) {
                if (c.type() != type) continue;
                BigDecimal pct = typeTotal.signum() == 0 ? BigDecimal.ZERO : c.total().amount().multiply(BigDecimal.valueOf(100)).divide(typeTotal, 1, RoundingMode.HALF_UP);
                rows.add(Row.of(type.name(), c.name(), c.total().amount(), pct.toPlainString() + "%"));
            }
        }
        return new ReportData("category-breakdown", "Category breakdown", period(from, to),
                List.of(Column.text("Type"), Column.text("Category"), Column.amount("Amount"), Column.count("Share of type")), rows, null, community.getCurrency());
    }

    // ---- billing reports ----------------------------------------------------------------------------------------------------

    /** Billed (issued invoices, not cancelled), collected (paid so far, net of reversals) and still outstanding, by period and fee plan. */
    private ReportData collection(Community community, LocalDate from, LocalDate to) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", community.getId()).addValue("from", from, java.sql.Types.DATE).addValue("to", to, java.sql.Types.DATE);
        List<Row> rows = new ArrayList<>();
        BigDecimal[] sums = {BigDecimal.ZERO, BigDecimal.ZERO};
        long[] count = {0};
        jdbc.query(
                "SELECT coalesce(i.period, '-') AS period, coalesce(fp.name, 'One-off: ' || i.kind) AS what, count(*) AS n, sum(i.amount) AS billed, sum(i.amount_paid) AS collected"
                        + " FROM invoices i LEFT JOIN fee_plans fp ON fp.id = i.fee_plan_id AND fp.community_id = i.community_id"
                        + " WHERE i.community_id = :c AND i.status NOT IN ('DRAFT', 'CANCELLED')"
                        + " AND (CAST(:from AS date) IS NULL OR i.issued_on >= CAST(:from AS date)) AND (CAST(:to AS date) IS NULL OR i.issued_on <= CAST(:to AS date))"
                        + " GROUP BY 1, 2 ORDER BY min(i.issued_on), 2",
                params, rs -> {
                    BigDecimal billed = rs.getBigDecimal("billed");
                    BigDecimal collected = rs.getBigDecimal("collected");
                    rows.add(Row.of(rs.getString("period"), rs.getString("what"), rs.getLong("n"), billed, collected, billed.subtract(collected), percent(collected, billed)));
                    sums[0] = sums[0].add(billed);
                    sums[1] = sums[1].add(collected);
                    count[0] += rs.getLong("n");
                });
        Row totals = Row.bold("Total", "", count[0], sums[0], sums[1], sums[0].subtract(sums[1]), percent(sums[1], sums[0]));
        List<String> context = new ArrayList<>(period(from, to));
        context.add("By invoice issue date. Billed excludes cancelled invoices; collected is net of reversed payments.");
        return new ReportData("collection", "Collection report: billed, collected and outstanding", context,
                List.of(Column.text("Period"), Column.text("Fee"), Column.count("Invoices"), Column.amount("Billed"), Column.amount("Collected"), Column.amount("Outstanding"), Column.count("Collected %")),
                rows, totals, community.getCurrency());
    }

    private ReportData memberDues(Community community, UUID memberId) {
        if (memberId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("memberId: required for the member dues statement"));
        }
        Member member = members.findByIdAndCommunityIdAndDeletedAtIsNull(memberId, community.getId()).orElseThrow(NotFoundException::new);
        List<Row> rows = new ArrayList<>();
        BigDecimal[] sums = {BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query(
                "SELECT i.invoice_no, coalesce(i.description, fp.name, i.kind) AS what, i.period, i.issued_on, i.due_date, i.amount, i.amount_paid, i.status"
                        + " FROM invoices i LEFT JOIN fee_plans fp ON fp.id = i.fee_plan_id AND fp.community_id = i.community_id"
                        + " WHERE i.community_id = :c AND i.member_id = :m AND i.status NOT IN ('DRAFT', 'CANCELLED') ORDER BY i.issued_on, i.invoice_no",
                new MapSqlParameterSource("c", community.getId()).addValue("m", memberId), rs -> {
                    BigDecimal amount = rs.getBigDecimal("amount");
                    BigDecimal paid = rs.getBigDecimal("amount_paid");
                    rows.add(Row.of(rs.getString("invoice_no"), rs.getString("what"), rs.getString("period"), rs.getObject("issued_on", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                            amount, paid, amount.subtract(paid), rs.getString("status")));
                    sums[0] = sums[0].add(amount);
                    sums[1] = sums[1].add(paid);
                });
        return new ReportData("member-dues", "Member dues statement", List.of("Member: " + member.getFullName() + " (" + member.getMemberNo() + ")", "As of " + LocalDate.now(clock.withZone(IST))),
                List.of(Column.text("Invoice"), Column.text("For"), Column.text("Period"), Column.text("Issued"), Column.text("Due"), Column.amount("Amount"), Column.amount("Paid"), Column.amount("Balance"), Column.text("Status")),
                rows, Row.bold("Total", "", "", null, null, sums[0], sums[1], sums[0].subtract(sums[1]), ""), community.getCurrency());
    }

    /** Members who owe something past its due date as of {@code asOf}, worst first. Worked out from the dates, not from the stored OVERDUE flag. */
    private ReportData defaulters(Community community, LocalDate asOf) {
        List<Row> rows = new ArrayList<>();
        BigDecimal[] total = {BigDecimal.ZERO};
        jdbc.query(
                "SELECT m.member_no, m.full_name, m.phone, m.email::text AS email, count(*) AS n, sum(i.amount - i.amount_paid) AS due, min(i.due_date) AS oldest"
                        + " FROM invoices i JOIN members m ON m.id = i.member_id AND m.community_id = i.community_id"
                        + " WHERE i.community_id = :c AND i.status IN ('ISSUED', 'PARTIAL', 'OVERDUE') AND i.due_date < :asOf AND i.amount > i.amount_paid"
                        + " GROUP BY m.id, m.member_no, m.full_name, m.phone, m.email ORDER BY due DESC, m.member_no",
                new MapSqlParameterSource("c", community.getId()).addValue("asOf", asOf), rs -> {
                    LocalDate oldest = rs.getObject("oldest", LocalDate.class);
                    BigDecimal due = rs.getBigDecimal("due");
                    rows.add(Row.of(rs.getString("member_no"), rs.getString("full_name"), rs.getString("phone"), rs.getString("email"), rs.getLong("n"), due, oldest, ChronoUnit.DAYS.between(oldest, asOf)));
                    total[0] = total[0].add(due);
                });
        return new ReportData("defaulters", "Defaulters: members with overdue dues", List.of("As of " + asOf),
                List.of(Column.text("Member no."), Column.text("Name"), Column.text("Phone"), Column.text("Email"), Column.count("Overdue invoices"), Column.amount("Overdue amount"), Column.text("Oldest due date"), Column.count("Days overdue")),
                rows, Row.bold("Total", "", "", "", (long) rows.size(), total[0], null, null), community.getCurrency());
    }

    private ReportData receiptsRegister(Community community, LocalDate from, LocalDate to) {
        MapSqlParameterSource params = new MapSqlParameterSource("c", community.getId()).addValue("from", from, java.sql.Types.DATE).addValue("to", to, java.sql.Types.DATE);
        List<Row> rows = new ArrayList<>();
        BigDecimal[] net = {BigDecimal.ZERO};
        jdbc.query(
                "SELECT r.receipt_no, p.received_on, coalesce(m.full_name, p.donor_name) AS payer, m.member_no, i.invoice_no, p.method, p.reference, p.amount, rv.received_on AS reversed_on"
                        + " FROM receipts r JOIN payment_records p ON p.id = r.payment_record_id AND p.community_id = r.community_id"
                        + " LEFT JOIN members m ON m.id = p.member_id AND m.community_id = p.community_id LEFT JOIN invoices i ON i.id = p.invoice_id AND i.community_id = p.community_id"
                        + " LEFT JOIN payment_records rv ON rv.community_id = p.community_id AND rv.reversed_of = p.id"
                        + " WHERE r.community_id = :c AND (CAST(:from AS date) IS NULL OR p.received_on >= CAST(:from AS date)) AND (CAST(:to AS date) IS NULL OR p.received_on <= CAST(:to AS date))"
                        + " ORDER BY r.receipt_no",
                params, rs -> {
                    boolean reversed = rs.getObject("reversed_on") != null;
                    BigDecimal amount = rs.getBigDecimal("amount");
                    rows.add(Row.of(rs.getString("receipt_no"), rs.getObject("received_on", LocalDate.class), rs.getString("payer"), rs.getString("member_no"), rs.getString("invoice_no"),
                            rs.getString("method"), rs.getString("reference"), amount, reversed ? "REVERSED " + rs.getObject("reversed_on", LocalDate.class) : "Valid"));
                    if (!reversed) net[0] = net[0].add(amount);
                });
        List<String> context = new ArrayList<>(period(from, to));
        context.add("The total counts receipts that have not been reversed.");
        return new ReportData("receipts-register", "Receipts register", context,
                List.of(Column.text("Receipt no."), Column.text("Date"), Column.text("Received from"), Column.text("Member no."), Column.text("Invoice"), Column.text("Method"), Column.text("Reference"), Column.amount("Amount"), Column.text("Status")),
                rows, Row.bold("Total received", null, "", "", "", "", "", net[0], ""), community.getCurrency());
    }

    private static String percent(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) return "0%";
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static List<String> period(LocalDate from, LocalDate to) {
        if (from == null && to == null) return List.of("All dates");
        return List.of("Period: " + (from == null ? "start" : from.toString()) + " to " + (to == null ? "today" : to.toString()));
    }
}
