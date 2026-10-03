package com.amanahconnect.ledger;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger entries that payments cause. They run inside the payment's own transaction (so the payment and its entry
 * stand or fall together) and are read-only afterwards: a payment is corrected by reversing it, which posts an offsetting
 * negative entry.
 */
@Service
public class LedgerPostings {

    private final LedgerEntryRepository entries;
    private final LedgerCategories categories;

    public LedgerPostings(LedgerEntryRepository entries, LedgerCategories categories) {
        this.entries = entries;
        this.categories = categories;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry postPayment(UUID communityId, String systemKey, BigDecimal amount, LocalDate date, String title, UUID paymentId, UUID actor) {
        LedgerCategory category = categories.systemCategory(communityId, systemKey);
        LedgerEntry entry = new LedgerEntry();
        entry.setCommunityId(communityId);
        entry.setType(LedgerType.INCOME);
        entry.setCategory(category);
        entry.setAmount(amount);
        entry.setEntryDate(date);
        entry.setTitle(title);
        entry.setSource(LedgerSource.PAYMENT);
        entry.setSourceId(paymentId);
        entry.setCreatedBy(actor);
        return entries.save(entry);
    }

    /** The negative twin of the entry the original payment produced. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry postReversal(UUID communityId, UUID originalPaymentId, UUID reversalPaymentId, LocalDate date, String reason, UUID actor) {
        LedgerEntry original = entries.findByCommunityIdAndSourceAndSourceId(communityId, LedgerSource.PAYMENT, originalPaymentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "The payment has no ledger entry to reverse."));
        LedgerEntry reversal = new LedgerEntry();
        reversal.setCommunityId(communityId);
        reversal.setType(original.getType());
        reversal.setCategory(original.getCategory());
        reversal.setAmount(original.getAmount().negate());
        reversal.setEntryDate(date);
        reversal.setTitle(truncate("Reversal: " + original.getTitle(), 200));
        reversal.setNotes(reason);
        reversal.setSource(LedgerSource.PAYMENT);
        reversal.setSourceId(reversalPaymentId);
        reversal.setReversedOf(original);
        reversal.setReversalReason(reason);
        reversal.setCreatedBy(actor);
        return entries.save(reversal);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
