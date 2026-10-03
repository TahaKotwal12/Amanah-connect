package com.amanahconnect.billing;

import com.amanahconnect.common.FinancialYear;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Billing periods and their due dates.
 *
 * <ul>
 *   <li>MONTHLY: {@code 2026-10}, due on the plan's due day of that month;
 *   <li>QUARTERLY: calendar quarters {@code 2026-Q4} (Oct to Dec), due in the quarter's first month;
 *   <li>YEARLY: the community's financial year, {@code 2026-27} (April start) or {@code 2026} (January start), due in
 *       its first month;
 *   <li>ONE_TIME: any short label chosen by the admin; its due date must be given.
 * </ul>
 *
 * A plan without a due day falls due on the 10th. Due days are 1 to 28, so every month has one.
 */
public final class FeePeriods {

    public static final int DEFAULT_DUE_DAY = 10;
    private static final Pattern MONTH = Pattern.compile("^(\\d{4})-(0[1-9]|1[0-2])$");
    private static final Pattern QUARTER = Pattern.compile("^(\\d{4})-Q([1-4])$");
    private static final Pattern YEAR_SPLIT = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final Pattern YEAR_CALENDAR = Pattern.compile("^(\\d{4})$");
    private static final Pattern FREE_LABEL = Pattern.compile("^[\\p{L}\\p{N} ._/-]{1,30}$");

    /** A period: its label, and its first day (null for a one-time label). */
    public record Period(String label, LocalDate start) {}

    private FeePeriods() {}

    /** The period that contains {@code today}. A one-time plan has none. */
    public static Period current(FeeFrequency frequency, LocalDate today, int fyStartMonth) {
        return switch (frequency) {
            case MONTHLY -> new Period("%d-%02d".formatted(today.getYear(), today.getMonthValue()), today.withDayOfMonth(1));
            case QUARTERLY -> {
                int quarter = (today.getMonthValue() - 1) / 3 + 1;
                yield new Period("%d-Q%d".formatted(today.getYear(), quarter), LocalDate.of(today.getYear(), (quarter - 1) * 3 + 1, 1));
            }
            case YEARLY -> new Period(FinancialYear.labelFor(today, fyStartMonth), FinancialYear.startOf(today, fyStartMonth));
            case ONE_TIME -> null;
        };
    }

    /** Parses a period an admin typed for this frequency, or says exactly what is wrong. */
    public static Period parse(FeeFrequency frequency, String text, int fyStartMonth) {
        String value = text == null ? "" : text.trim();
        switch (frequency) {
            case MONTHLY -> {
                Matcher m = MONTH.matcher(value);
                if (m.matches()) {
                    return new Period(value, LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1));
                }
                throw bad("period must look like 2026-10 for a monthly plan");
            }
            case QUARTERLY -> {
                Matcher m = QUARTER.matcher(value);
                if (m.matches()) {
                    return new Period(value, LocalDate.of(Integer.parseInt(m.group(1)), (Integer.parseInt(m.group(2)) - 1) * 3 + 1, 1));
                }
                throw bad("period must look like 2026-Q4 for a quarterly plan");
            }
            case YEARLY -> {
                if (fyStartMonth == 1) {
                    Matcher m = YEAR_CALENDAR.matcher(value);
                    if (m.matches()) {
                        return new Period(value, LocalDate.of(Integer.parseInt(value), 1, 1));
                    }
                    throw bad("period must look like 2026 (this community's financial year is the calendar year)");
                }
                Matcher m = YEAR_SPLIT.matcher(value);
                if (m.matches() && Integer.parseInt(m.group(2)) == (Integer.parseInt(m.group(1)) + 1) % 100) {
                    return new Period(value, LocalDate.of(Integer.parseInt(m.group(1)), fyStartMonth, 1));
                }
                throw bad("period must look like 2026-27 (a financial year)");
            }
            case ONE_TIME -> {
                if (FREE_LABEL.matcher(value).matches()) {
                    return new Period(value, null);
                }
                throw bad("period is required for a one-time plan: 1 to 30 letters, digits, spaces and . _ / -");
            }
        }
        throw new IllegalStateException();
    }

    /** When an invoice for this period falls due, or null for a one-time plan (the admin gives the date). */
    public static LocalDate dueDate(Period period, Short dueDay) {
        if (period.start() == null) {
            return null;
        }
        int day = dueDay == null ? DEFAULT_DUE_DAY : dueDay;
        return period.start().withDayOfMonth(day);
    }

    private static ApiException bad(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", List.of("period: " + message));
    }
}
