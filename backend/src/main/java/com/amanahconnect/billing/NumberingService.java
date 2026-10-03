package com.amanahconnect.billing;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues gap-free, duplicate-free document numbers such as {@code RCP-2026-27/000123}.
 *
 * <p>The counter row is locked with {@code SELECT ... FOR UPDATE} and incremented inside the
 * <em>caller's</em> transaction ({@link Propagation#MANDATORY}). That is what makes the sequence
 * gap-free: if the invoice or receipt insert fails and the transaction rolls back, the increment
 * rolls back with it and the number is handed out again. A database sequence cannot do that.
 *
 * <p>The price is that issuers of the same type, community and year are serialised until commit, so
 * keep the surrounding transaction short.
 */
@Service
public class NumberingService {

    private final DocumentCounterRepository counters;

    public NumberingService(DocumentCounterRepository counters) {
        this.counters = counters;
    }

    /**
     * @param financialYear label from {@link com.amanahconnect.common.FinancialYear#labelFor}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID communityId, CounterType type, String financialYear) {
        counters.ensureExists(communityId, type.name(), financialYear);
        DocumentCounter counter =
                counters.findByCommunityIdAndCounterTypeAndFinancialYear(
                                communityId, type, financialYear)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Counter row missing right after ensureExists"));
        counter.setLastValue(counter.getLastValue() + 1);
        counters.save(counter);
        return format(type, financialYear, counter.getLastValue());
    }

    /**
     * Reserves {@code count} consecutive values of a counter and returns the first one, with one row update. Used
     * for member numbers (scope {@code "ALL"}: they never restart), including bulk imports.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long reserve(UUID communityId, CounterType type, String scope, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        counters.ensureExists(communityId, type.name(), scope);
        DocumentCounter counter = counters.findByCommunityIdAndCounterTypeAndFinancialYear(communityId, type, scope)
                .orElseThrow(() -> new IllegalStateException("Counter row missing right after ensureExists"));
        long first = counter.getLastValue() + 1;
        counter.setLastValue(counter.getLastValue() + count);
        counters.save(counter);
        return first;
    }

    static String format(CounterType type, String financialYear, long value) {
        return "%s-%s/%06d".formatted(type.prefix(), financialYear, value);
    }
}
