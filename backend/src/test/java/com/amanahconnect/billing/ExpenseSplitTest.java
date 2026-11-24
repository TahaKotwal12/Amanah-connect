package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseSplitTest {

    private static BigDecimal sum(List<BigDecimal> shares) {
        return shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void anEvenDivisionGivesEveryoneTheSame() {
        assertThat(ExpenseSplit.equal(new BigDecimal("1000.00"), 4)).containsExactly(
                new BigDecimal("250.00"), new BigDecimal("250.00"), new BigDecimal("250.00"), new BigDecimal("250.00"));
    }

    @Test
    void theLeftoverPaiseGoOneEachToTheFirstShares() {
        List<BigDecimal> shares = ExpenseSplit.equal(new BigDecimal("1000.00"), 3);
        assertThat(shares).containsExactly(new BigDecimal("333.34"), new BigDecimal("333.33"), new BigDecimal("333.33"));
        assertThat(sum(shares)).isEqualByComparingTo("1000.00");
    }

    @Test
    void twoPaiseLeftoverGoToTwoMembers() {
        List<BigDecimal> shares = ExpenseSplit.equal(new BigDecimal("100.02"), 4);
        assertThat(shares).containsExactly(new BigDecimal("25.01"), new BigDecimal("25.01"), new BigDecimal("25.00"), new BigDecimal("25.00"));
        assertThat(sum(shares)).isEqualByComparingTo("100.02");
    }

    @Test
    void oneMemberPaysTheWholeAmount() {
        assertThat(ExpenseSplit.equal(new BigDecimal("12.34"), 1)).containsExactly(new BigDecimal("12.34"));
    }

    @Test
    void sharesAlwaysAddUpAndNeverDifferByMoreThanAPaisa() {
        for (int members = 1; members <= 40; members++) {
            for (String amount : List.of("0.99", "1.00", "7.77", "100.00", "12345.67", "999999.99")) {
                BigDecimal total = new BigDecimal(amount);
                if (total.compareTo(new BigDecimal("0.01").multiply(BigDecimal.valueOf(members))) < 0) continue;
                List<BigDecimal> shares = ExpenseSplit.equal(total, members);
                assertThat(shares).hasSize(members);
                assertThat(sum(shares)).as("%s over %d", amount, members).isEqualByComparingTo(total);
                BigDecimal min = shares.stream().min(BigDecimal::compareTo).orElseThrow();
                BigDecimal max = shares.stream().max(BigDecimal::compareTo).orElseThrow();
                assertThat(max.subtract(min)).isLessThanOrEqualTo(new BigDecimal("0.01"));
                assertThat(min).isGreaterThan(BigDecimal.ZERO);
            }
        }
    }

    @Test
    void needsAtLeastOneMember() {
        assertThatThrownBy(() -> ExpenseSplit.equal(new BigDecimal("10.00"), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
