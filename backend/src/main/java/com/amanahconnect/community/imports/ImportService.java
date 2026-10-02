package com.amanahconnect.community.imports;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.Community;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.community.CommunityStatus;
import com.amanahconnect.community.imports.ImportModel.BalanceRow;
import com.amanahconnect.community.imports.ImportModel.InvoiceRow;
import com.amanahconnect.community.imports.ImportModel.MemberRow;
import com.amanahconnect.community.imports.ImportModel.StoredRows;
import com.amanahconnect.community.imports.ImportReport.FileReport;
import com.amanahconnect.community.imports.ImportReport.Summary;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.plan.PlanLimitKeys;
import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.plan.PlanSnapshot;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Onboarding an existing community from CSV, in two explicit steps:
 *
 * <ol>
 *   <li><b>Dry run</b> ({@link #dryRun}): parse and validate every row, store the validated rows under the client's
 *       batch id and return the report. Nothing in the community changes.
 *   <li><b>Confirm</b> ({@link #confirm}): apply exactly the stored rows in one transaction, after re-checking plan
 *       limits and uniqueness against the community's current data.
 * </ol>
 *
 * <p>Both are idempotent per {@code (community, batchId)}: repeating a dry run with the same files returns the same
 * report, repeating a confirm returns the stored result, and reusing a batch id for different files is a 409. An
 * advisory lock per batch serialises concurrent calls with the same id.
 *
 * <p>All writes are plain INSERTs scoped to the community id (a super admin has no tenant context). Imported open
 * invoices carry only what is still owed ({@code amount}, {@code amount_paid = 0}) with the old system's number,
 * and take no number from the gap-free counters.
 */
@Service
public class ImportService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_CONFLICTS_LISTED = 20;

    public record Outcome(ImportReport report, boolean created) {}

    private final CommunityRepository communities;
    private final ImportBatchRepository batches;
    private final MemberRepository members;
    private final CsvParser parser;
    private final ImportValidator validator;
    private final PlanLimitService planLimits;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public ImportService(
            CommunityRepository communities,
            ImportBatchRepository batches,
            MemberRepository members,
            CsvParser parser,
            ImportValidator validator,
            PlanLimitService planLimits,
            NamedParameterJdbcTemplate jdbc,
            AuditService audit,
            JsonMapper json,
            Clock clock) {
        this.communities = communities;
        this.batches = batches;
        this.members = members;
        this.parser = parser;
        this.validator = validator;
        this.planLimits = planLimits;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---- step 1: dry run --------------------------------------------------------------------------------

    @Transactional
    public Outcome dryRun(UUID communityId, UUID batchId, byte[] membersCsv, byte[] balancesCsv, byte[] invoicesCsv) {
        Community community = community(communityId);
        requireOpen(community);
        if (membersCsv == null && balancesCsv == null && invoicesCsv == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Upload at least one CSV file: members, openingBalances or openInvoices.");
        }
        CsvTable memberTable = membersCsv == null ? null : parser.parse("members", membersCsv);
        CsvTable balanceTable = balancesCsv == null ? null : parser.parse("openingBalances", balancesCsv);
        CsvTable invoiceTable = invoicesCsv == null ? null : parser.parse("openInvoices", invoicesCsv);
        String hash = hash(membersCsv, balancesCsv, invoicesCsv);

        lock(communityId, batchId);
        ImportBatch existing = batches.findByCommunityIdAndBatchId(communityId, batchId).orElse(null);
        if (existing != null) {
            if (!existing.getPayloadHash().equals(hash)) {
                throw new ApiException(ErrorCode.IMPORT_BATCH_CONFLICT, "This batchId was already used for different files. Use a new batchId for a new upload.");
            }
            return new Outcome(view(existing), false);
        }

        LocalDate today = LocalDate.now(clock.withZone(IST));
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        var memberResult = memberTable == null ? null : validator.members(communityId, memberTable, today);
        Set<String> batchMembers = new HashSet<>();
        Map<String, Integer> failedMembers = new HashMap<>();
        if (memberResult != null) {
            memberResult.rows().forEach(r -> batchMembers.add(r.memberNo()));
            memberResult.report().invalid().forEach(r -> {
                if (r.key() != null && !r.key().isEmpty()) failedMembers.put(r.key(), r.row());
            });
        }
        var balanceResult = balanceTable == null ? null : validator.openingBalances(communityId, balanceTable, today);
        var invoiceResult = invoiceTable == null ? null : validator.openInvoices(communityId, invoiceTable, today, batchMembers, failedMembers);

        FileReport memberReport = memberResult == null ? FileReport.notProvided() : memberResult.report();
        FileReport balanceReport = balanceResult == null ? FileReport.notProvided() : balanceResult.report();
        FileReport invoiceReport = invoiceResult == null ? FileReport.notProvided() : invoiceResult.report();
        collect("members", memberReport, blocking, warnings);
        collect("openingBalances", balanceReport, blocking, warnings);
        collect("openInvoices", invoiceReport, blocking, warnings);

        int newMembers = memberResult == null ? 0 : memberResult.rows().size();
        PlanSnapshot plan = planLimits.planOf(communityId);
        Long limit = plan.limits().get(PlanLimitKeys.MAX_MEMBERS) instanceof Number n ? n.longValue() : null;
        long current = members.countByCommunityIdAndDeletedAtIsNull(communityId);
        if (limit != null && current + newMembers > limit) {
            blocking.add("The %s plan allows %d members. The community has %d and this import would add %d, which is %d over the limit. Upgrade the plan or import fewer members."
                    .formatted(plan.name(), limit, current, newMembers, current + newMembers - limit));
        }

        int validRows = memberReport.validRows() + balanceReport.validRows() + invoiceReport.validRows();
        int invalidRows = memberReport.invalidRows() + balanceReport.invalidRows() + invoiceReport.invalidRows();
        if (validRows == 0) {
            blocking.add("There are no valid rows to import.");
        }
        boolean confirmable = blocking.isEmpty();

        ImportReport report = new ImportReport(
                batchId, "DRY_RUN", confirmable, new Summary(validRows, invalidRows, newMembers, limit, current),
                memberReport, balanceReport, invoiceReport, blocking, warnings, null);
        StoredRows rows = new StoredRows(
                memberResult == null ? List.of() : memberResult.rows(),
                balanceResult == null ? List.of() : balanceResult.rows(),
                invoiceResult == null ? List.of() : invoiceResult.rows());

        ImportBatch batch = new ImportBatch();
        batch.setCommunityId(communityId);
        batch.setBatchId(batchId);
        batch.setPayloadHash(hash);
        batch.setReport(toMap(report));
        batch.setRows(toMap(rows));
        batch.setConfirmable(confirmable);
        batch.setCreatedBy(AuditService.currentActorId());
        batches.save(batch);

        audit.recordForCommunity("COMMUNITY_IMPORT_DRY_RUN", communityId, "ImportBatch", batch.getId(), null,
                Map.of("batchId", batchId.toString(), "validRows", validRows, "invalidRows", invalidRows, "confirmable", confirmable));
        return new Outcome(report, true);
    }

    // ---- step 2: confirm ----------------------------------------------------------------------------------

    @Transactional
    public ImportReport confirm(UUID communityId, UUID batchId, boolean skipInvalidRows) {
        Community community = community(communityId);
        lock(communityId, batchId);
        ImportBatch batch = batches.findWithLockByCommunityIdAndBatchId(communityId, batchId).orElseThrow(NotFoundException::new);
        if (batch.getStatus() == ImportBatchStatus.CONFIRMED) {
            return view(batch); // a retry: the stored result, nothing applied twice
        }
        requireOpen(community);
        ImportReport report = view(batch);
        StoredRows rows = json.convertValue(batch.getRows(), StoredRows.class);

        if (!rows.members().isEmpty()) {
            planLimits.checkMemberLimit(communityId, rows.members().size()); // 402 when the plan no longer fits
        }
        if (!batch.isConfirmable()) {
            throw new ApiException(ErrorCode.IMPORT_NOT_CONFIRMABLE, "This import cannot be confirmed: " + String.join(" ", report.blockingErrors()), report.blockingErrors());
        }
        int invalid = report.summary().invalidRows();
        if (invalid > 0 && !skipInvalidRows) {
            throw new ApiException(ErrorCode.IMPORT_NOT_CONFIRMABLE,
                    "%d rows failed validation. Fix the files and upload again, or confirm with skipInvalidRows=true to import only the valid rows.".formatted(invalid));
        }
        recheck(communityId, rows);
        UUID actor = AuditService.currentActorId();

        applyMembers(communityId, rows.members());
        applyBalances(communityId, rows.openingBalances(), actor);
        applyInvoices(communityId, rows.openInvoices());

        BigDecimal outstanding = rows.openInvoices().stream().map(i -> new BigDecimal(i.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("membersCreated", rows.members().size());
        result.put("openingBalancesCreated", rows.openingBalances().size());
        result.put("openInvoicesCreated", rows.openInvoices().size());
        result.put("outstandingInvoiceTotal", Money.of(outstanding).toString());
        result.put("skippedInvalidRows", invalid);

        batch.setStatus(ImportBatchStatus.CONFIRMED);
        batch.setSkipInvalid(skipInvalidRows);
        batch.setResult(result);
        batch.setConfirmedBy(actor);
        batch.setConfirmedAt(clock.instant());
        batches.save(batch);

        Map<String, Object> auditAfter = new LinkedHashMap<>(result);
        auditAfter.put("batchId", batchId.toString());
        auditAfter.put("skipInvalidRows", skipInvalidRows);
        audit.recordForCommunity("COMMUNITY_IMPORT_CONFIRMED", communityId, "ImportBatch", batch.getId(), null, auditAfter);
        return view(batch);
    }

    @Transactional(readOnly = true)
    public ImportReport get(UUID communityId, UUID batchId) {
        community(communityId);
        return view(batches.findByCommunityIdAndBatchId(communityId, batchId).orElseThrow(NotFoundException::new));
    }

    // ---- applying ------------------------------------------------------------------------------------------

    private void recheck(UUID communityId, StoredRows rows) {
        List<String> conflicts = new ArrayList<>();
        Set<String> batchMembers = new HashSet<>();
        for (MemberRow m : rows.members()) batchMembers.add(m.memberNo());

        validator.existingMemberNos(communityId, new ArrayList<>(batchMembers), false)
                .forEach(no -> conflicts.add("member_no " + no + " now exists in this community"));
        validator.existingInvoiceNos(communityId, rows.openInvoices().stream().map(InvoiceRow::invoiceNo).toList())
                .forEach(no -> conflicts.add("invoice_no " + no + " now exists in this community"));
        List<String> referenced = rows.openInvoices().stream().map(InvoiceRow::memberNo).filter(no -> !batchMembers.contains(no)).distinct().toList();
        Set<String> present = validator.existingMemberNos(communityId, referenced, true);
        referenced.stream().filter(no -> !present.contains(no)).forEach(no -> conflicts.add("member " + no + " no longer exists in this community"));
        Set<UUID> categories = new HashSet<>();
        jdbc.query("SELECT id FROM ledger_categories WHERE community_id = :c AND active", new MapSqlParameterSource("c", communityId), rs -> { categories.add(rs.getObject(1, UUID.class)); });
        rows.openingBalances().stream().filter(b -> !categories.contains(b.categoryId()))
                .forEach(b -> conflicts.add("category '" + b.categoryName() + "' is no longer active"));
        if (!conflicts.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_CONFLICT,
                    "The community's data changed since the dry run. Upload the files again with a new batchId.",
                    conflicts.size() > MAX_CONFLICTS_LISTED ? conflicts.subList(0, MAX_CONFLICTS_LISTED) : conflicts);
        }
    }

    private void applyMembers(UUID communityId, List<MemberRow> rows) {
        batch("INSERT INTO members (id, community_id, member_no, full_name, email, phone, group_label, status, joined_on, consent_email)"
                        + " VALUES (gen_random_uuid(), CAST(:c AS uuid), :memberNo, :fullName, CAST(:email AS citext), CAST(:phone AS varchar),"
                        + " CAST(:group AS varchar), :status, CAST(:joinedOn AS date), :consent)",
                rows, m -> {
                    Map<String, Object> p = new HashMap<>();
                    p.put("c", communityId.toString());
                    p.put("memberNo", m.memberNo());
                    p.put("fullName", m.fullName());
                    p.put("email", m.email());
                    p.put("phone", m.phone());
                    p.put("group", m.group());
                    p.put("status", m.status());
                    p.put("joinedOn", m.joinedOn());
                    p.put("consent", m.consentEmail());
                    return p;
                });
    }

    private void applyBalances(UUID communityId, List<BalanceRow> rows, UUID actor) {
        batch("INSERT INTO ledger_entries (id, community_id, type, category_id, amount, entry_date, title, source, created_by)"
                        + " VALUES (gen_random_uuid(), CAST(:c AS uuid), :type, CAST(:cat AS uuid), CAST(:amount AS numeric), CAST(:asOf AS date), :title, 'MANUAL', CAST(:by AS uuid))",
                rows, b -> {
                    Map<String, Object> p = new HashMap<>();
                    p.put("c", communityId.toString());
                    p.put("type", b.type());
                    p.put("cat", b.categoryId().toString());
                    p.put("amount", b.amount());
                    p.put("asOf", b.asOf());
                    p.put("title", b.title());
                    p.put("by", actor.toString());
                    return p;
                });
    }

    private void applyInvoices(UUID communityId, List<InvoiceRow> rows) {
        if (rows.isEmpty()) return;
        int[] counts = jdbc.batchUpdate(
                "INSERT INTO invoices (id, community_id, member_id, invoice_no, kind, period, amount, amount_paid, due_date, status)"
                        + " SELECT gen_random_uuid(), m.community_id, m.id, CAST(:invoiceNo AS varchar), CAST(:kind AS varchar), CAST(:period AS varchar),"
                        + " CAST(:amount AS numeric), 0, CAST(:due AS date), CAST(:status AS varchar)"
                        + " FROM members m WHERE m.community_id = CAST(:c AS uuid) AND m.member_no = :memberNo AND m.deleted_at IS NULL",
                rows.stream().map(i -> {
                    Map<String, Object> p = new HashMap<>();
                    p.put("c", communityId.toString());
                    p.put("memberNo", i.memberNo());
                    p.put("invoiceNo", i.invoiceNo());
                    p.put("kind", i.kind());
                    p.put("period", i.period());
                    p.put("amount", i.amount());
                    p.put("due", i.dueDate());
                    p.put("status", i.status());
                    return (SqlParameterSource) new MapSqlParameterSource(p);
                }).toArray(SqlParameterSource[]::new));
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) {
                throw new ApiException(ErrorCode.IMPORT_CONFLICT, "Member " + rows.get(i).memberNo() + " no longer exists; nothing was imported.");
            }
        }
    }

    private <T> void batch(String sql, List<T> rows, Function<T, Map<String, Object>> params) {
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(sql, rows.stream().map(r -> (SqlParameterSource) new MapSqlParameterSource(params.apply(r))).toArray(SqlParameterSource[]::new));
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private Community community(UUID id) {
        return communities.findById(id).orElseThrow(NotFoundException::new);
    }

    private static void requireOpen(Community community) {
        if (community.getStatus() == CommunityStatus.ARCHIVED) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "This community is archived. Activate it before importing.");
        }
    }

    private static void collect(String file, FileReport report, List<String> blocking, List<String> warnings) {
        report.errors().forEach(e -> blocking.add(file + ": " + e));
        report.warnings().forEach(w -> warnings.add(file + ": " + w));
    }

    private void lock(UUID communityId, UUID batchId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", new MapSqlParameterSource("key", "import:" + communityId + ":" + batchId), rs -> { });
    }

    private ImportReport view(ImportBatch batch) {
        ImportReport stored = json.convertValue(batch.getReport(), ImportReport.class);
        return stored.with(batch.getStatus().name(), batch.getResult());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return json.convertValue(value, Map.class);
    }

    private static String hash(byte[]... files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] file : files) {
                if (file == null) {
                    digest.update((byte) 0);
                } else {
                    digest.update((byte) 1);
                    digest.update(java.nio.ByteBuffer.allocate(8).putLong(file.length).array());
                    digest.update(file);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
