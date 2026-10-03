package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FormatsTest {

    @Test
    void usesLakhGroupingForRupees() {
        assertThat(Formats.amount(new BigDecimal("1234567.5"), "INR")).isEqualTo("12,34,567.50");
        assertThat(Formats.amount(new BigDecimal("999"), "INR")).isEqualTo("999.00");
        assertThat(Formats.amount(new BigDecimal("1000"), "INR")).isEqualTo("1,000.00");
        assertThat(Formats.amount(new BigDecimal("100000"), "INR")).isEqualTo("1,00,000.00");
    }

    @Test
    void usesThousandsGroupingForOtherCurrencies() {
        assertThat(Formats.amount(new BigDecimal("1234567.5"), "USD")).isEqualTo("1,234,567.50");
    }

    @Test
    void keepsTheSignAndRoundsHalfUp() {
        assertThat(Formats.amount(new BigDecimal("-1500.005"), "INR")).isEqualTo("-1,500.01");
        assertThat(Formats.amount(BigDecimal.ZERO, "INR")).isEqualTo("0.00");
    }
}
