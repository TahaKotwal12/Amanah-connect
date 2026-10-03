package com.amanahconnect.billing;

import com.amanahconnect.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The status of an invoice that has been issued and not cancelled, worked out from its amounts and due date alone (so
 * it can always be recomputed and never drifts):
 *
 * <ul>
 *   <li>PAID when everything has been paid;
 *   <li>OVERDUE when anything is still owed and the due date has passed (a partly paid invoice that is late is OVERDUE);
 *   <li>PARTIAL when something, not everything, has been paid and it is not late;
 *   <li>ISSUED when nothing has been paid and it is not late.
 * </ul>
 *
 * "Late" means the due date is before today (IST); on the due date itself an invoice is not late.
 */
public final class InvoiceStatusRules {

    private InvoiceStatusRules() {}

    public static InvoiceStatus derive(BigDecimal amount, BigDecimal amountPaid, LocalDate dueDate, LocalDate today) {
        Money total = Money.of(amount);
        Money paid = Money.of(amountPaid);
        if (paid.compareTo(total) >= 0) {
            return InvoiceStatus.PAID;
        }
        if (dueDate.isBefore(today)) {
            return InvoiceStatus.OVERDUE;
        }
        return paid.isPositive() ? InvoiceStatus.PARTIAL : InvoiceStatus.ISSUED;
    }

    /** What is still owed: amount minus everything paid net of reversals. */
    public static Money balance(BigDecimal amount, BigDecimal amountPaid) {
        return Money.of(amount).minus(Money.of(amountPaid));
    }

    /** Whether a payment can be recorded against an invoice in this status. */
    public static boolean acceptsPayments(InvoiceStatus status) {
        return status == InvoiceStatus.ISSUED || status == InvoiceStatus.PARTIAL || status == InvoiceStatus.OVERDUE;
    }
}
