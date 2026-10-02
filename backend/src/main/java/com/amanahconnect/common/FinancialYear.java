package com.amanahconnect.common;

import java.time.LocalDate;

/** Financial-year labels used in document numbers, e.g. {@code 2026-27} (April start) or {@code 2026}. */
public final class FinancialYear {

    private FinancialYear() {}

    /** The first day of the financial year that contains {@code date}. */
    public static LocalDate startOf(LocalDate date, int startMonth) {
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("startMonth must be 1..12, was " + startMonth);
        }
        int startYear = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return LocalDate.of(startYear, startMonth, 1);
    }

    /**
     * @param startMonth first month of the community's financial year, 1 to 12 (4 = April)
     */
    public static String labelFor(LocalDate date, int startMonth) {
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("startMonth must be 1..12, was " + startMonth);
        }
        if (startMonth == 1) {
            return String.valueOf(date.getYear());
        }
        int startYear = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return "%d-%02d".formatted(startYear, (startYear + 1) % 100);
    }
}
