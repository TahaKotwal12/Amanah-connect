package com.amanahconnect.billing;

import com.amanahconnect.common.money.Money;
import java.math.BigDecimal;

/**
 * An amount in words for a receipt. Rupees use the Indian grouping (thousand, lakh, crore):
 * {@code 1234567.50} is "Rupees Twelve Lakh Thirty-Four Thousand Five Hundred Sixty-Seven and Fifty Paise Only".
 * Other currencies use the code and western grouping: "USD One Thousand Two Hundred and 05/100 Only".
 */
public final class AmountInWords {

    private static final String[] ONES = {
        "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen",
        "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"
    };
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private AmountInWords() {}

    public static String of(BigDecimal amount, String currency) {
        Money money = Money.of(amount);
        if (money.isNegative()) {
            throw new IllegalArgumentException("A receipt amount is never negative");
        }
        BigDecimal value = money.amount();
        long major = value.toBigInteger().longValueExact();
        int minor = value.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
        if ("INR".equals(currency)) {
            String words = "Rupees " + indian(major);
            if (minor > 0) {
                words += " and " + below100(minor) + " Paise";
            }
            return words + " Only";
        }
        String code = currency == null ? "" : currency + " ";
        return code + western(major) + (minor > 0 ? " and %02d/100".formatted(minor) : "") + " Only";
    }

    static String indian(long n) {
        if (n == 0) {
            return "Zero";
        }
        StringBuilder out = new StringBuilder();
        long crore = n / 10_000_000L;
        long lakh = (n / 100_000L) % 100;
        long thousand = (n / 1_000L) % 100;
        long rest = n % 1_000L;
        if (crore > 0) append(out, indian(crore) + " Crore");
        if (lakh > 0) append(out, below100((int) lakh) + " Lakh");
        if (thousand > 0) append(out, below100((int) thousand) + " Thousand");
        if (rest > 0) append(out, below1000((int) rest));
        return out.toString();
    }

    static String western(long n) {
        if (n == 0) {
            return "Zero";
        }
        StringBuilder out = new StringBuilder();
        String[] names = {"", "Thousand", "Million", "Billion", "Trillion"};
        int[] groups = new int[5];
        long rest = n;
        for (int i = 0; i < 5 && rest > 0; i++) {
            groups[i] = (int) (rest % 1000);
            rest /= 1000;
        }
        for (int i = 4; i >= 0; i--) {
            if (groups[i] > 0) {
                append(out, below1000(groups[i]) + (names[i].isEmpty() ? "" : " " + names[i]));
            }
        }
        return out.toString();
    }

    private static String below1000(int n) {
        if (n < 100) {
            return below100(n);
        }
        String head = ONES[n / 100] + " Hundred";
        return n % 100 == 0 ? head : head + " " + below100(n % 100);
    }

    private static String below100(int n) {
        if (n < 20) {
            return ONES[n];
        }
        return n % 10 == 0 ? TENS[n / 10] : TENS[n / 10] + "-" + ONES[n % 10];
    }

    private static void append(StringBuilder out, String words) {
        if (out.length() > 0) {
            out.append(' ');
        }
        out.append(words);
    }
}
