package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.billing.BillingDtos.GenerateInvoicesRequest;
import com.amanahconnect.billing.BillingDtos.GenerateResult;
import com.amanahconnect.common.FinancialYear;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.community.Community;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.member.MemberStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bills a fee plan for a period. Safe to repeat: one invoice per fee plan, member and period is enforced by a unique index,
 * and the whole run is serialised per plan and period, so asking twice (or two admins asking at once) bills nobody twice and
 * burns no invoice numbers. Numbers come from the gap-free sequence, reserved in one step for exactly the invoices created.
 */
@Service
@Transactional
public class InvoiceGenerationService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final FeePlanService feePlans;
    private final InvoiceService invoiceService;
    private final InvoiceRepository invoices;
    private final MemberRepository members;
    private final NumberingService numbering;
    private final BillingEmails emails;
    private final BillingProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final jakarta.persistence.EntityManager em;
    private final Clock clock;

    public InvoiceGenerationService(
            FeePlanService feePlans,
            InvoiceService invoiceService,
            InvoiceRepository invoices,
            MemberRepository members,
            NumberingService numbering,
            BillingEmails emails,
            BillingProperties properties,
            NamedParameterJdbcTemplate jdbc,
            AuditService audit,
            jakarta.persistence.EntityManager em,
            Clock clock) {
        this.feePlans = feePlans;
        this.invoiceService = invoiceService;
        this.invoices = invoices;
        this.members = members;
        this.numbering = numbering;
        this.emails = emails;
        this.properties = properties;
        this.jdbc = jdbc;
        this.audit = audit;
        this.em = em;
        this.clock = clock;
    }

    public GenerateResult generate(UUID communityId, GenerateInvoicesRequest request) {
        FeePlan plan = feePlans.find(communityId, request.feePlanId());
        return generate(communityId, plan, request.period(), request.dueDate(), !Boolean.FALSE.equals(request.sendEmails()));
    }

    /** @param periodText null for the current period */
    public GenerateResult generate(UUID communityId, FeePlan plan, String periodText, LocalDate dueOverride, boolean sendEmails) {
        if (!plan.isActive()) {
            throw new ApiException(ErrorCode.FEE_PLAN_INACTIVE, "This fee plan is switched off. Switch it on before billing it.");
        }
        Community community = invoiceService.community(communityId);
        int fyMonth = community.getFinancialYearStartMonth();
        LocalDate today = LocalDate.now(clock.withZone(IST));

        FeePeriods.Period period = periodText == null || periodText.isBlank()
                ? FeePeriods.current(plan.getFrequency(), today, fyMonth)
                : FeePeriods.parse(plan.getFrequency(), periodText, fyMonth);
        if (period == null) {
            throw invalid("period", "required for a one-time plan");
        }
        LocalDate due = dueOverride != null ? dueOverride : FeePeriods.dueDate(period, plan.getDueDay());
        if (due == null) {
            throw invalid("dueDate", "required for a one-time plan");
        }

        lock(communityId, plan.getId(), period.label());

        List<Member> audience = audience(communityId, plan);
        List<Member> eligible = audience.stream().filter(m -> m.getStatus() == MemberStatus.ACTIVE).sorted(Comparator.comparing(Member::getMemberNo).thenComparing(Member::getId)).toList();
        int skippedInactive = audience.size() - eligible.size();
        if (eligible.size() > properties.generationMaxMembers()) {
            throw invalid("feePlanId", "too many members for one run (" + eligible.size() + "); bill a group instead");
        }
        Set<UUID> alreadyBilledIds = billedMembers(communityId, plan.getId(), period.label());
        List<Member> toBill = eligible.stream().filter(m -> !alreadyBilledIds.contains(m.getId())).toList();
        int alreadyBilled = eligible.size() - toBill.size();

        String first = null;
        String last = null;
        int queued = 0, noAddress = 0, noConsent = 0, quota = 0;
        List<Invoice> created = new ArrayList<>();
        if (!toBill.isEmpty()) {
            String fy = FinancialYear.labelFor(today, fyMonth);
            long start = numbering.reserve(communityId, CounterType.INVOICE, fy, toBill.size());
            for (int i = 0; i < toBill.size(); i++) {
                Invoice invoice = new Invoice();
                invoice.setCommunityId(communityId);
                invoice.setMember(toBill.get(i));
                invoice.setFeePlan(plan);
                invoice.setKind(plan.getKind());
                invoice.setPeriod(period.label());
                invoice.setDescription(plan.getName());
                invoice.setAmount(plan.getAmount());
                invoice.setDueDate(due);
                invoice.setIssuedOn(today);
                invoice.setInvoiceNo(NumberingService.format(CounterType.INVOICE, fy, start + i));
                invoice.setStatus(InvoiceStatusRules.derive(plan.getAmount(), java.math.BigDecimal.ZERO, due, today));
                invoices.save(invoice);
                created.add(invoice);
            }
            first = created.get(0).getInvoiceNo();
            last = created.get(created.size() - 1).getInvoiceNo();
            em.flush();
            if (sendEmails) {
                BillingEmails.Session session = emails.session(community);
                for (Invoice invoice : created) {
                    switch (invoiceService.emailBill(community, invoice, session, true)) {
                        case QUEUED -> queued++;
                        case NO_ADDRESS -> noAddress++;
                        case NO_CONSENT -> noConsent++;
                        case QUOTA -> quota++;
                        case DISABLED -> { }
                    }
                }
            }
        }
        FeePeriods.Period currentNow = FeePeriods.current(plan.getFrequency(), today, fyMonth);
        if (plan.isAutoGenerate() && currentNow != null && currentNow.label().equals(period.label())) {
            plan.setLastGeneratedPeriod(period.label()); // billed by hand: the daily job has nothing left to do for this period
        }
        GenerateResult result = new GenerateResult(plan.getId(), period.label(), due, eligible.size(), created.size(), alreadyBilled, skippedInactive, queued, noAddress, noConsent, quota, first, last);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("feePlanId", plan.getId().toString());
        after.put("period", period.label());
        after.put("created", created.size());
        after.put("alreadyBilled", alreadyBilled);
        after.put("firstInvoiceNo", first);
        after.put("lastInvoiceNo", last);
        audit.record("INVOICES_GENERATED", AuditService.currentActorId(), communityId, "FeePlan", plan.getId(), null, after);
        return result;
    }

    private List<Member> audience(UUID communityId, FeePlan plan) {
        return switch (plan.getAppliesTo()) {
            case ALL_ACTIVE -> members.findByCommunityIdAndStatusAndDeletedAtIsNull(communityId, MemberStatus.ACTIVE);
            case GROUP -> members.findGroupMembersByCommunityId(communityId, MemberStatus.ACTIVE, ((String) plan.getAppliesToFilter().get("group")).toLowerCase());
            case SELECTED -> members.findByCommunityIdAndIdInAndDeletedAtIsNull(communityId, FeePlanService.memberIdsOf(plan));
        };
    }

    private Set<UUID> billedMembers(UUID communityId, UUID planId, String period) {
        Set<UUID> ids = new HashSet<>();
        jdbc.query("SELECT member_id FROM invoices WHERE community_id = :c AND fee_plan_id = :p AND period = :per AND status <> 'CANCELLED'",
                new MapSqlParameterSource("c", communityId).addValue("p", planId).addValue("per", period),
                rs -> { ids.add(rs.getObject(1, UUID.class)); });
        return ids;
    }

    private void lock(UUID communityId, UUID planId, String period) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", new MapSqlParameterSource("key", "billing-generate:" + communityId + ":" + planId + ":" + period), rs -> { });
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}
