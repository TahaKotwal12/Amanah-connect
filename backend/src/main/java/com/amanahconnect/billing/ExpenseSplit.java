package com.amanahconnect.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Divides an amount into equal shares to the paisa. Every share is the total divided by the number of members, rounded
 * down; what is left over (always less than one paisa per member) is handed out one paisa at a time to the first shares.
 * So the shares always add up to exactly the total, and no two shares differ by more than one paisa.
 */
final class ExpenseSplit {

    private static final BigDecimal PAISA = new BigDecimal("0.01");

    private ExpenseSplit() {}

    static List<BigDecimal> equal(BigDecimal total, int members) {
        if (members < 1) {
            throw new IllegalArgumentException("at least one member");
        }
        BigDecimal base = total.divide(BigDecimal.valueOf(members), 2, RoundingMode.DOWN);
        int extraPaise = total.subtract(base.multiply(BigDecimal.valueOf(members))).divide(PAISA, 0, RoundingMode.UNNECESSARY).intValueExact();
        List<BigDecimal> shares = new ArrayList<>(members);
        for (int i = 0; i < members; i++) {
            shares.add(i < extraPaise ? base.add(PAISA) : base);
        }
        return shares;
    }
}
