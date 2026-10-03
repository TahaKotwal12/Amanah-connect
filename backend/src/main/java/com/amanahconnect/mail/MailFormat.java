package com.amanahconnect.mail;

import com.amanahconnect.billing.Formats;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Display formatting for email templates, available there as {@code fmt}. Never throws: a value it cannot read is shown as it is. */
public final class MailFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public MailFormat() {}

    /** {@code 2026-05-10} as {@code 10 May 2026}. */
    public static String date(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            return DATE.format(LocalDate.parse(iso.length() > 10 ? iso.substring(0, 10) : iso));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    /** {@code 1500} and {@code INR} as {@code ₹1,500.00}. */
    public static String money(Object amount, Object currency) {
        if (amount == null) return "";
        String code = currency == null || String.valueOf(currency).isBlank() ? "INR" : String.valueOf(currency);
        try {
            String formatted = Formats.amount(new BigDecimal(String.valueOf(amount)), code);
            return ("INR".equals(code) ? "₹" : code + " ") + formatted;
        } catch (RuntimeException e) {
            return String.valueOf(amount);
        }
    }

    // Thymeleaf calls instance methods through the model variable.
    public String d(Object iso) {
        return date(iso == null ? null : String.valueOf(iso));
    }

    public String m(Object amount, Object currency) {
        return money(amount, currency);
    }
}
