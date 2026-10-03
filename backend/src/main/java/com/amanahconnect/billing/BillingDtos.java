package com.amanahconnect.billing;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.money.MoneyAmount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class BillingDtos {

    private BillingDtos() {}

    // ---- fee plans -------------------------------------------------------------------------------------------

    public record CreateFeePlanRequest(
            @NotBlank @Size(max = 150) String name,
            @NotNull FeeKind kind,
            @NotNull @MoneyAmount(positive = true) BigDecimal amount,
            @NotNull FeeFrequency frequency,
            @Min(1) @Max(28) Integer dueDay,
            FeeAudience appliesTo,
            /** For GROUP: the group's name, matched without regard to case. */
            @Size(max = 100) String group,
            /** For SELECTED: the members to bill. */
            @Size(max = 2000) List<UUID> memberIds,
            /** Bill every period automatically (off unless you say so). */
            Boolean autoGenerate) {}

    /** Partial. The kind and frequency never change: they define what a period means. */
    public record UpdateFeePlanRequest(
            @Size(min = 1, max = 150) String name,
            @MoneyAmount(positive = true) BigDecimal amount,
            @Min(1) @Max(28) Integer dueDay,
            FeeAudience appliesTo,
            @Size(max = 100) String group,
            @Size(max = 2000) List<UUID> memberIds,
            Boolean active,
            Boolean autoGenerate) {}

    public record FeePlanView(
            UUID id,
            String name,
            FeeKind kind,
            Money amount,
            FeeFrequency frequency,
            Integer dueDay,
            FeeAudience appliesTo,
            String group,
            List<UUID> memberIds,
            boolean active,
            boolean autoGenerate,
            String lastGeneratedPeriod,
            /** The period an automatic run would bill today; null for a one-time plan. */
            String currentPeriod,
            Instant createdAt) {}

    // ---- invoices ---------------------------------------------------------------------------------------------

    public record InvoiceView(
            UUID id,
            String invoiceNo,
            InvoiceStatus status,
            FeeKind kind,
            UUID memberId,
            String memberNo,
            String memberName,
            UUID feePlanId,
            String period,
            String description,
            Money amount,
            Money amountPaid,
            Money balance,
            LocalDate issuedOn,
            LocalDate dueDate,
            String cancelReason,
            Instant cancelledAt,
            long version,
            Instant createdAt) {}

    public record InvoiceDetail(InvoiceView invoice, List<PaymentView> payments) {}

    public record CreateInvoiceRequest(
            @NotNull UUID memberId,
            @NotNull FeeKind kind,
            @NotBlank @Size(max = 200) String description,
            @NotNull @MoneyAmount(positive = true) BigDecimal amount,
            @NotNull LocalDate dueDate,
            @Size(max = 30) String period,
            /** Keep it as a draft: no number, no email, not payable until issued. */
            Boolean draft,
            /** Email the bill when it is issued (default true). */
            Boolean sendEmail) {}

    /** Drafts only. */
    public record UpdateInvoiceRequest(
            FeeKind kind,
            @Size(min = 1, max = 200) String description,
            @MoneyAmount(positive = true) BigDecimal amount,
            LocalDate dueDate,
            @Size(max = 30) String period) {}

    public record IssueInvoiceRequest(Boolean sendEmail) {}

    public record CancelInvoiceRequest(@NotBlank @Size(max = 500) String reason) {}

    public record GenerateInvoicesRequest(
            @NotNull UUID feePlanId,
            /** Defaults to the current period; required for a one-time plan. */
            @Size(max = 30) String period,
            /** Defaults from the period and the plan's due day; required for a one-time plan. */
            LocalDate dueDate,
            /** Email each bill (default true). */
            Boolean sendEmails) {}

    public record GenerateResult(
            UUID feePlanId,
            String period,
            LocalDate dueDate,
            int eligibleMembers,
            int created,
            int alreadyBilled,
            int skippedInactive,
            int emailsQueued,
            int emailsSkippedNoAddress,
            int emailsSkippedNoConsent,
            int emailsSkippedQuota,
            String firstInvoiceNo,
            String lastInvoiceNo) {}

    public record PayLinkView(String url, Instant expiresAt) {}

    // ---- payments ----------------------------------------------------------------------------------------------

    public record RecordPaymentRequest(
            @NotNull @MoneyAmount(positive = true) BigDecimal amount,
            @NotNull PaymentMethod method,
            @Size(max = 100) String reference,
            /** Defaults to today. Not in the future. */
            LocalDate receivedOn,
            /** Optional: the invoice version you were looking at. A stale one is refused with 409. */
            Long expectedVersion) {}

    public record ReversePaymentRequest(@NotBlank @Size(max = 500) String reason, LocalDate reversedOn) {}

    /** A donation from a member or from an anonymous donor: exactly one of memberId and donorName. */
    public record RecordDonationRequest(
            UUID memberId,
            @Size(max = 150) String donorName,
            @NotNull @MoneyAmount(positive = true) BigDecimal amount,
            @NotNull PaymentMethod method,
            @Size(max = 100) String reference,
            LocalDate receivedOn) {}

    public record PaymentView(
            UUID id,
            /** PAYMENT, DONATION or REVERSAL. */
            String kind,
            UUID invoiceId,
            String invoiceNo,
            UUID memberId,
            String memberNo,
            String payerName,
            /** Negative for a reversal. */
            Money amount,
            PaymentMethod method,
            String reference,
            LocalDate receivedOn,
            UUID reversedOf,
            String reversalReason,
            /** True when a reversal of this payment exists. */
            boolean reversed,
            UUID receiptId,
            String receiptNo,
            Instant createdAt) {}

    /** The result of recording a payment, a donation or a reversal. */
    public record PaymentResult(PaymentView payment, InvoiceView invoice, boolean replayed) {}

    public record ReceiptView(
            UUID id,
            String receiptNo,
            UUID paymentId,
            UUID invoiceId,
            String invoiceNo,
            String payerName,
            Money amount,
            PaymentMethod method,
            String reference,
            LocalDate receivedOn,
            boolean reversed,
            LocalDate reversedOn,
            String reversalReason,
            Instant emailedAt,
            Instant createdAt) {}

    // ---- public payment page ------------------------------------------------------------------------------------

    public record UpiPayment(String payeeName, String link, String qrCodePngBase64) {}

    /** What a person with a payment link sees: the community, the one invoice, and how to pay. Nothing about anyone else. */
    public record PublicPayView(
            String communityName,
            String logoUrl,
            String invoiceNo,
            String description,
            String period,
            String currency,
            Money amount,
            Money amountPaid,
            Money balanceDue,
            LocalDate dueDate,
            InvoiceStatus status,
            boolean payable,
            /** Null when the invoice cannot be paid (settled, cancelled) or the community has no UPI ID. */
            UpiPayment upi,
            boolean manualConfirmation,
            String notice) {}
}
