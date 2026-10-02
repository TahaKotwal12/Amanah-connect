package com.amanahconnect.common.money;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

public class MoneyAmountValidator implements ConstraintValidator<MoneyAmount, Object> {

    private boolean positive;
    private boolean nonNegative;

    @Override
    public void initialize(MoneyAmount constraint) {
        this.positive = constraint.positive();
        this.nonNegative = constraint.nonNegative();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        BigDecimal amount;
        try {
            amount = toBigDecimal(value);
        } catch (RuntimeException e) {
            return fail(context, "must be a valid amount, for example 1250.50");
        }
        if (amount.stripTrailingZeros().scale() > Money.SCALE) {
            return fail(context, "must have at most 2 decimals");
        }
        if (amount.abs().compareTo(Money.LIMIT) >= 0) {
            return fail(context, "is too large");
        }
        if (positive && amount.signum() <= 0) {
            return fail(context, "must be greater than zero");
        }
        if (nonNegative && amount.signum() < 0) {
            return fail(context, "must not be negative");
        }
        return true;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof Money money) {
            return money.amount();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof CharSequence text) {
            return Money.parse(text.toString()).amount();
        }
        throw new IllegalArgumentException("unsupported type " + value.getClass());
    }

    private static boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
