package com.amanahconnect.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FinancialYearTest {

    @Test
    void aprilStartRunsAprilToMarch() {
        assertThat(FinancialYear.labelFor(LocalDate.of(2026, 4, 1), 4)).isEqualTo("2026-27");
        assertThat(FinancialYear.labelFor(LocalDate.of(2027, 3, 31), 4)).isEqualTo("2026-27");
        assertThat(FinancialYear.labelFor(LocalDate.of(2027, 4, 1), 4)).isEqualTo("2027-28");
        assertThat(FinancialYear.labelFor(LocalDate.of(2026, 1, 15), 4)).isEqualTo("2025-26");
    }

    @Test
    void januaryStartIsTheCalendarYear() {
        assertThat(FinancialYear.labelFor(LocalDate.of(2026, 12, 31), 1)).isEqualTo("2026");
        assertThat(FinancialYear.labelFor(LocalDate.of(2026, 1, 1), 1)).isEqualTo("2026");
    }

    @Test
    void centuryRolloverKeepsTwoDigitSuffix() {
        assertThat(FinancialYear.labelFor(LocalDate.of(2099, 5, 1), 4)).isEqualTo("2099-00");
    }

    @Test
    void rejectsInvalidStartMonth() {
        assertThatThrownBy(() -> FinancialYear.labelFor(LocalDate.of(2026, 1, 1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FinancialYear.labelFor(LocalDate.of(2026, 1, 1), 13))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
