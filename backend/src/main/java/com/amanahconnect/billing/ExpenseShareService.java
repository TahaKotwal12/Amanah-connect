package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.billing.ExpenseShareDtos.DivideExpenseRequest;
import com.amanahconnect.billing.ExpenseShareDtos.DivideResult;
import com.amanahconnect.billing.ExpenseShareDtos.ShareView;
import com.amanahconnect.billing.ExpenseShareDtos.SharesView;
import com.amanahconnect.common.FinancialYear;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.community.Community;
import com.amanahconnect.ledger.LedgerEntry;
import com.amanahconnect.ledger.LedgerEntryRepository;
import com.amanahconnect.ledger.LedgerSource;
import com.amanahconnect.ledger.LedgerType;
import com.amanahconnect.member.Member;
import com.amanahconnect.member.MemberRepository;
import com.amanahconnect.member.MemberStatus;
import com.amanahconnect.tenant.TenantGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Divides an expense among members. The admin records an expense (money the community spent), then asks to collect it
 * back from members: each chosen member gets an invoice for an equal share, with a gap-free number, and the bill is emailed.
 * The member pays cash, the admin records it on that invoice, and the receipt is emailed (the ordinary payment flow).
 *
 * <p>An expense is divided once. Dividing again is refused while any share is still live; cancel the unpaid shares first
 * (shares with money received are reversed, never deleted).
 */
@Service
@Transactional
public class ExpenseShareService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LedgerEntryRepository entries;
    private final MemberRepository members;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final NumberingService numbering;
    private final BillingEmails emails;
    private final BillingProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final jakarta.persistence.EntityManager em;
    private final Clock clock;

    public ExpenseShareService(
            LedgerEntryRepository entries,
            MemberRepository members,
            InvoiceRepository invoices,
            InvoiceService invoiceService,
            NumberingService numbering,
            BillingEmails emails,
            BillingProperties properties,
            NamedParameterJdbcTemplate jdbc,
            AuditService audit,
            TenantGuard tenantGuard,
            jakarta.persistence.EntityManager em,
            Clock clock) {
        this.entries = entries;
        this.members = members;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.numbering = numbering;
        this.emails = emails;
        this.properties = properties;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.em = em;
        this.clock = clock;
    }

    public DivideResult divide(UUID communityId, UUID expenseId, DivideExpenseRequest request) {
        LedgerEntry expense = tenantGuard.found(entries.findByIdAndCommunityId(expenseId, communityId));
        requireDividable(communityId, expense);
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (request.dueDate().isBefore(today)) {
            throw invalid("dueDate", "must not be in the past");
        }

        // One division of one expense at a time: a double click or two admins at once queue here, and the second sees the first's shares.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))", new MapSqlParameterSource("key", "expense-divide:" + communityId + ":" + expenseId), rs -> { });
        Integer live = jdbc.queryForObject(
                "SELECT count(*) FROM invoices WHERE community_id = :c AND expense_entry_id = :e AND status <> 'CANCELLED'",
                new MapSqlParameterSource("c", communityId).addValue("e", expenseId), Integer.class);
        if (live != null && live > 0) {
            throw new ApiException(ErrorCode.EXPENSE_ALREADY_DIVIDED, "This expense has already been divided. Cancel the unpaid shares first to divide it again.");
        }

        List<Member> chosen = chooseMembers(communityId, request.memberIds());
        BigDecimal total = expense.getAmount();
        if (total.compareTo(new BigDecimal("0.01").multiply(BigDecimal.valueOf(chosen.size()))) < 0) {
            throw invalid("memberIds", "the amount is too small to divide among " + chosen.size() + " members");
        }
        List<BigDecimal> shares = ExpenseSplit.equal(total, chosen.size());

        Community community = invoiceService.community(communityId);
        String fy = FinancialYear.labelFor(today, community.getFinancialYearStartMonth());
        long start = numbering.reserve(communityId, CounterType.INVOICE, fy, chosen.size());
        String description = truncate("Share of: " + expense.getTitle(), 200);
        List<Invoice> created = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            Invoice invoice = new Invoice();
            invoice.setCommunityId(communityId);
            invoice.setMember(chosen.get(i));
            invoice.setKind(FeeKind.OTHER);
            invoice.setDescription(description);
            invoice.setAmount(shares.get(i));
            invoice.setDueDate(request.dueDate());
            invoice.setIssuedOn(today);
            invoice.setExpenseEntryId(expenseId);
            invoice.setInvoiceNo(NumberingService.format(CounterType.INVOICE, fy, start + i));
            invoice.setStatus(InvoiceStatusRules.derive(shares.get(i), BigDecimal.ZERO, request.dueDate(), today));
            invoices.save(invoice);
            created.add(invoice);
        }
        em.flush();

        int queued = 0, noAddress = 0, noConsent = 0, quota = 0;
        if (!Boolean.FALSE.equals(request.sendEmails())) {
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

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("expenseTitle", expense.getTitle());
        after.put("total", total.toPlainString());
        after.put("members", chosen.size());
        after.put("firstInvoiceNo", created.get(0).getInvoiceNo());
        after.put("lastInvoiceNo", created.get(created.size() - 1).getInvoiceNo());
        audit.record("EXPENSE_DIVIDED", AuditService.currentActorId(), communityId, "LedgerEntry", expenseId, null, after);

        return new DivideResult(expenseId, Money.of(total), chosen.size(), Money.of(shares.get(0)), Money.of(shares.get(shares.size() - 1)), queued, noAddress, noConsent, quota);
    }

    @Transactional(readOnly = true)
    public SharesView shares(UUID communityId, UUID expenseId) {
        LedgerEntry expense = tenantGuard.found(entries.findByIdAndCommunityId(expenseId, communityId));
        List<Invoice> all = invoices.findByCommunityIdAndExpenseEntryId(communityId, expenseId).stream()
                .sorted(Comparator.comparing(Invoice::getInvoiceNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        BigDecimal divided = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;
        List<ShareView> views = new ArrayList<>();
        for (Invoice invoice : all) {
            Member member = invoice.getMember();
            Money amount = Money.of(invoice.getAmount());
            Money paid = Money.of(invoice.getAmountPaid());
            views.add(new ShareView(invoice.getId(), invoice.getInvoiceNo(), member.getId(), member.getMemberNo(), member.getFullName(),
                    amount, paid, amount.minus(paid), invoice.getStatus(), invoice.getDueDate()));
            if (invoice.getStatus() != InvoiceStatus.CANCELLED) {
                divided = divided.add(invoice.getAmount());
                collected = collected.add(invoice.getAmountPaid());
            }
        }
        return new SharesView(expenseId, expense.getTitle(), Money.of(expense.getAmount()), Money.of(divided), Money.of(collected), Money.of(divided.subtract(collected)), views);
    }

    // ---- rules ---------------------------------------------------------------------------------------------------------

    /** Only a plain, current expense can be divided: not income, not a payment's posting, not a reversal, not one that was reversed. */
    private void requireDividable(UUID communityId, LedgerEntry expense) {
        boolean ok = expense.getType() == LedgerType.EXPENSE
                && expense.getSource() == LedgerSource.MANUAL
                && expense.getReversedOf() == null
                && !entries.existsByCommunityIdAndReversedOfId(communityId, expense.getId());
        if (!ok) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Only an expense that has not been reversed can be divided among members.");
        }
    }

    private List<Member> chooseMembers(UUID communityId, List<UUID> requested) {
        List<Member> chosen;
        if (requested == null || requested.isEmpty()) {
            chosen = new ArrayList<>(members.findByCommunityIdAndStatusAndDeletedAtIsNull(communityId, MemberStatus.ACTIVE));
        } else {
            List<UUID> distinct = requested.stream().distinct().toList();
            chosen = new ArrayList<>(members.findByCommunityIdAndIdInAndDeletedAtIsNull(communityId, distinct));
            // An id from another community, or a deleted member, is simply not found: the same answer as an unknown id.
            if (chosen.size() != distinct.size()) {
                throw new ApiException(ErrorCode.NOT_FOUND, "One of the members was not found.");
            }
            if (chosen.stream().anyMatch(m -> m.getStatus() != MemberStatus.ACTIVE)) {
                throw invalid("memberIds", "only active members can be billed");
            }
        }
        if (chosen.isEmpty()) {
            throw invalid("memberIds", "there are no active members to divide it among");
        }
        if (chosen.size() > properties.generationMaxMembers()) {
            throw invalid("memberIds", "too many members for one division (" + chosen.size() + ")");
        }
        chosen.sort(Comparator.comparing(Member::getMemberNo).thenComparing(Member::getId));
        return chosen;
    }

    private static String truncate(String text, int max) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= max ? single : single.substring(0, max);
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of(field + ": " + message));
    }
}
