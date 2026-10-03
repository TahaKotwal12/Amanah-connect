package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.billing.BillingDtos.GenerateResult;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two daily billing sweeps. Both are safe to repeat and to run twice at once.
 *
 * <p><b>Generation</b>: for every fee plan that is active and set to bill automatically, in an ACTIVE community, bills the
 * current period if the plan has not billed it yet. {@code last_generated_period} on the plan is the gate, the unique
 * invoice key is the safety net. One plan failing does not stop the others (each runs in its own transaction).
 *
 * <p><b>Overdue</b>: ISSUED and PARTIAL invoices whose due date has passed become OVERDUE, in one statement (a payment that is
 * being recorded at the same moment either lands first, or the invoice is flipped and the payment keeps it overdue).
 */
@Service
public class BillingJobService {

    private static final Logger log = LoggerFactory.getLogger(BillingJobService.class);

    public record GenerationReport(int plansBilled, int invoicesCreated, int plansSkipped, int failures) {}

    public record OverdueReport(int invoicesMarked, int communities) {}

    private record Candidate(UUID communityId, UUID planId) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final FeePlanRepository feePlans;
    private final InvoiceGenerationService generation;
    private final FeePlanService feePlanService;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    public BillingJobService(
            NamedParameterJdbcTemplate jdbc,
            FeePlanRepository feePlans,
            InvoiceGenerationService generation,
            FeePlanService feePlanService,
            AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.feePlans = feePlans;
        this.generation = generation;
        this.feePlanService = feePlanService;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public GenerationReport generateDue(LocalDate today) {
        List<Candidate> candidates = jdbc.query(
                "SELECT fp.community_id, fp.id FROM fee_plans fp JOIN communities c ON c.id = fp.community_id"
                        + " WHERE fp.active AND fp.auto_generate AND fp.frequency <> 'ONE_TIME' AND c.status = 'ACTIVE' ORDER BY fp.community_id, fp.id",
                new MapSqlParameterSource(), (rs, row) -> new Candidate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        int billed = 0, created = 0, skipped = 0, failures = 0;
        for (Candidate candidate : candidates) {
            try {
                int[] outcome = transaction.execute(status -> billOne(candidate, today));
                if (outcome[0] == 1) {
                    billed++;
                    created += outcome[1];
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failures++;
                log.error("Automatic billing failed for fee plan {}: {}", candidate.planId(), e.toString());
            }
        }
        GenerationReport report = new GenerationReport(billed, created, skipped, failures);
        log.info("Billing sweep for {}: {}", today, report);
        return report;
    }

    private int[] billOne(Candidate candidate, LocalDate today) {
        FeePlan plan = feePlans.findByIdAndCommunityId(candidate.planId(), candidate.communityId()).orElseThrow();
        int fyMonth = feePlanService.fyMonth(candidate.communityId());
        FeePeriods.Period period = FeePeriods.current(plan.getFrequency(), today, fyMonth);
        if (period == null || period.label().equals(plan.getLastGeneratedPeriod())) {
            return new int[] {0, 0};
        }
        GenerateResult result = generation.generate(candidate.communityId(), plan, period.label(), null, true);
        plan.setLastGeneratedPeriod(period.label());
        feePlans.save(plan);
        return new int[] {1, result.created()};
    }

    public OverdueReport markOverdue(LocalDate today) {
        record Hit(UUID communityId, UUID invoiceId) {}
        List<Hit> hits = transaction.execute(status -> jdbc.query(
                "UPDATE invoices SET status = 'OVERDUE', version = version + 1 WHERE status IN ('ISSUED', 'PARTIAL') AND due_date < :today RETURNING community_id, id",
                new MapSqlParameterSource("today", today), (rs, row) -> new Hit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))));
        Map<UUID, Integer> perCommunity = new LinkedHashMap<>();
        hits.forEach(h -> perCommunity.merge(h.communityId(), 1, Integer::sum));
        perCommunity.forEach((communityId, count) -> transaction.executeWithoutResult(status ->
                audit.record("INVOICES_MARKED_OVERDUE", null, communityId, "Invoice", null, null, Map.of("count", count, "asOf", today.toString()))));
        OverdueReport report = new OverdueReport(hits.size(), perCommunity.size());
        log.info("Overdue sweep for {}: {}", today, report);
        return report;
    }
}
