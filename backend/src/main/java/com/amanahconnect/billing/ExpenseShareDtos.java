package com.amanahconnect.billing;

import com.amanahconnect.common.money.Money;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for dividing an expense among members. Money is a string in JSON. */
public final class ExpenseShareDtos {

    private ExpenseShareDtos() {}

    /**
     * @param memberIds the members to divide it among; leave out (or empty) for every active member
     * @param dueDate when the bill is due
     * @param sendEmails email each member their bill (default true)
     */
    public record DivideExpenseRequest(List<UUID> memberIds, @NotNull LocalDate dueDate, Boolean sendEmails) {}

    public record DivideResult(
            UUID expenseId,
            Money total,
            int members,
            Money firstShare,
            Money lastShare,
            int emailsQueued,
            int emailsSkippedNoAddress,
            int emailsSkippedNoConsent,
            int emailsSkippedQuota) {}

    /** One member's share: an ordinary invoice. */
    public record ShareView(
            UUID invoiceId,
            String invoiceNo,
            UUID memberId,
            String memberNo,
            String memberName,
            Money amount,
            Money amountPaid,
            Money balance,
            InvoiceStatus status,
            LocalDate dueDate) {}

    public record SharesView(
            UUID expenseId,
            String title,
            Money expenseAmount,
            /** Sum of the live (not cancelled) shares. */
            Money divided,
            Money collected,
            Money pending,
            List<ShareView> shares) {}
}
