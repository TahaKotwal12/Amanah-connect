package com.amanahconnect.billing;

import com.amanahconnect.billing.BillingDtos.InvoiceView;
import com.amanahconnect.billing.BillingDtos.PaymentView;
import com.amanahconnect.common.money.Money;
import com.amanahconnect.member.Member;
import java.util.Set;
import java.util.UUID;

/** Entity to DTO mapping for billing. Call inside a transaction (members and receipts are lazy). */
final class Views {

    private Views() {}

    static InvoiceView invoice(Invoice i) {
        Member m = i.getMember();
        String description = i.getDescription() != null ? i.getDescription() : (i.getFeePlan() == null ? null : i.getFeePlan().getName());
        Money amount = Money.of(i.getAmount());
        Money paid = Money.of(i.getAmountPaid());
        return new InvoiceView(
                i.getId(), i.getInvoiceNo(), i.getStatus(), i.getKind(), m.getId(), m.getMemberNo(), m.getFullName(),
                i.getFeePlan() == null ? null : i.getFeePlan().getId(), i.getPeriod(), description, amount, paid, amount.minus(paid),
                i.getIssuedOn(), i.getDueDate(), i.getCancelReason(), i.getCancelledAt(), i.getVersion(), i.getCreatedAt());
    }

    /** @param reversedIds ids of payments that have a reversal */
    static PaymentView payment(PaymentRecord p, Set<UUID> reversedIds) {
        Member m = p.getMember();
        String kind = p.getReversedOf() != null ? "REVERSAL" : p.getInvoice() == null ? "DONATION" : "PAYMENT";
        Receipt receipt = p.getReceipt();
        return new PaymentView(
                p.getId(), kind, p.getInvoice() == null ? null : p.getInvoice().getId(), p.getInvoice() == null ? null : p.getInvoice().getInvoiceNo(),
                m == null ? null : m.getId(), m == null ? null : m.getMemberNo(), m == null ? p.getDonorName() : m.getFullName(), Money.of(p.getAmount()),
                p.getMethod(), p.getReference(), p.getReceivedOn(), p.getReversedOf() == null ? null : p.getReversedOf().getId(), p.getReversalReason(),
                reversedIds.contains(p.getId()), receipt == null ? null : receipt.getId(), receipt == null ? null : receipt.getReceiptNo(), p.getCreatedAt());
    }
}
