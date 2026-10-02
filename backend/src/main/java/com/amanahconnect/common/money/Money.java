package com.amanahconnect.common.money;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * An amount of money: always a {@link BigDecimal} with exactly two decimals, rounded HALF_UP, within
 * the {@code NUMERIC(14,2)} range of the database. Never a double or float. It serialises to JSON as a
 * string ({@code "1250.50"}).
 *
 * <p>Currency is a property of the community, not of the amount, so it is not carried here.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    /** Largest magnitude NUMERIC(14,2) can hold: 12 integer digits. */
    public static final BigDecimal LIMIT = new BigDecimal("1000000000000");

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private static final Pattern PLAIN = Pattern.compile("^-?\\d{1,12}(\\.\\d{1,2})?$");

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        amount = amount.setScale(SCALE, ROUNDING);
        if (amount.abs().compareTo(LIMIT) >= 0) {
            throw new IllegalArgumentException("amount is outside the supported range");
        }
    }

    /** Rounds HALF_UP to two decimals. Use for computed values (percentages, splits). */
    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    /**
     * Strict parse for input: plain decimal text with at most two decimals ({@code "10"}, {@code "10.5"},
     * {@code "-3.25"}). Rejects exponents, thousands separators, blanks and extra decimals instead of
     * rounding what a person typed.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Money parse(String text) {
        if (text == null || !PLAIN.matcher(text.trim()).matches()) {
            throw new IllegalArgumentException("not a valid amount (expected e.g. 1250.50, at most 2 decimals)");
        }
        return new Money(new BigDecimal(text.trim()));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(amount.negate());
    }

    /** Multiplies and rounds HALF_UP, e.g. for a percentage. */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor));
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    /** Plain text with two decimals, never scientific notation. Also the JSON form. */
    @JsonValue
    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
