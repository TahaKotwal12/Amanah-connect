package com.amanahconnect.billing;

import com.amanahconnect.common.money.Money;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The standard UPI payment deep link: {@code upi://pay?pa=<VPA>&pn=<payee>&am=<amount>&cu=INR&tn=<note>}. Every UPI app
 * understands it, and it is what the QR code on a bill encodes. The payee name and note are percent-encoded; the VPA is
 * validated (letters, digits and a few symbols plus one @) so it can sit in the link as it is.
 */
public final class UpiLinks {

    private static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.\\-_]{1,255}@[a-zA-Z][a-zA-Z0-9]{1,63}$");
    /** UPI apps truncate or reject long notes. */
    static final int NOTE_MAX = 50;

    private UpiLinks() {}

    public static String build(String vpa, String payeeName, Money amount, String note) {
        if (vpa == null || !VPA.matcher(vpa).matches()) {
            throw new IllegalArgumentException("not a valid UPI ID");
        }
        if (payeeName == null || payeeName.isBlank()) {
            throw new IllegalArgumentException("a payee name is required");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("the amount must be greater than zero");
        }
        String shortNote = note == null ? "" : note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note;
        return "upi://pay?pa=" + vpa + "&pn=" + encode(payeeName.trim()) + "&am=" + amount + "&cu=INR&tn=" + encode(shortNote);
    }

    /** RFC 3986 percent-encoding: only unreserved characters stay; a space is %20 (never +). */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16))).append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
