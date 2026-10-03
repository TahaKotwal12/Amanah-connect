package com.amanahconnect.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money as people read it. */
public final class Formats {

    private Formats() {}

    /** {@code 1234567.5} as "12,34,567.50" for INR (lakh grouping) and "1,234,567.50" otherwise. */
    public static String amount(BigDecimal value, String currency) {
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        String plain = scaled.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String fraction = plain.substring(dot);
        StringBuilder out = new StringBuilder();
        if ("INR".equals(currency) && whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            String tail = whole.substring(whole.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) out.append(',');
                out.append(head.charAt(i));
            }
            out.append(',').append(tail);
        } else {
            for (int i = 0; i < whole.length(); i++) {
                if (i > 0 && (whole.length() - i) % 3 == 0) out.append(',');
                out.append(whole.charAt(i));
            }
        }
        return (scaled.signum() < 0 ? "-" : "") + out + fraction;
    }
}
