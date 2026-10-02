package com.amanahconnect.common.money;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates an amount supplied by a client: a {@code BigDecimal}, {@link Money} or numeric string with
 * at most two decimals and at most 12 integer digits (the NUMERIC(14,2) range). Null is valid; add
 * {@code @NotNull} to require it.
 */
@Documented
@Constraint(validatedBy = MoneyAmountValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MoneyAmount {

    String message() default "must be a valid amount with at most 2 decimals";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Require the amount to be greater than zero. */
    boolean positive() default false;

    /** Require the amount to be zero or greater. */
    boolean nonNegative() default false;
}
