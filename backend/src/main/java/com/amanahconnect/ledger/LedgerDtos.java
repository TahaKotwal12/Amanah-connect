package com.amanahconnect.ledger;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.money.MoneyAmount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LedgerDtos {

    private LedgerDtos() {}

    public record CategoryView(UUID id, String name, LedgerType type, boolean active, boolean system, long entryCount) {}

    public record CreateCategoryRequest(@NotBlank @Size(max = 100) String name, @NotNull LedgerType type) {}

    /** The type never changes (entries carry it). The system categories can be renamed but not hidden. */
    public record UpdateCategoryRequest(@Size(min = 1, max = 100) String name, Boolean active) {}

    public record CategoryRef(UUID id, String name) {}

    public record EntryView(
            UUID id,
            LedgerType type,
            CategoryRef category,
            /** Negative for a reversal. */
            Money amount,
            LocalDate entryDate,
            String title,
            String notes,
            String attachmentKey,
            String attachmentUrl,
            LedgerSource source,
            UUID sourceId,
            UUID reversedOf,
            String reversalReason,
            /** True when a reversal of this entry exists. */
            boolean reversed,
            /** Payment entries (and reversals) cannot be edited: reverse the payment instead. */
            boolean readOnly,
            Instant createdAt) {}

    public record CreateEntryRequest(
            @NotNull LedgerType type,
            @NotNull UUID categoryId,
            @NotNull @MoneyAmount(positive = true) BigDecimal amount,
            @NotNull LocalDate entryDate,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 2000) String notes,
            /** The key from the attachment upload-url call, after the file was uploaded. */
            @Size(max = 255) String attachmentKey) {}

    /** Manual entries only. The amount and type never change: reverse the entry and add a new one. */
    public record UpdateEntryRequest(
            UUID categoryId,
            LocalDate entryDate,
            @Size(min = 1, max = 200) String title,
            @Size(max = 2000) String notes,
            /** An empty string removes the attachment. */
            @Size(max = 255) String attachmentKey) {}

    public record ReverseEntryRequest(@NotBlank @Size(max = 500) String reason, LocalDate entryDate) {}

    public record AttachmentUploadRequest(@NotBlank @Size(max = 100) String contentType, @NotNull Long sizeBytes) {}

    public record AttachmentUploadView(String uploadUrl, String method, Map<String, String> headers, String attachmentKey, Instant expiresAt, long maxBytes) {}

    public record CategoryTotal(UUID categoryId, String name, LedgerType type, Money total) {}

    public record MonthTotal(String month, Money income, Money expense, Money net) {}

    /** Totals are net of reversals. closingBalance = openingBalance + net, where the opening balance carries on from the community's setting and everything before {@code from}. */
    public record SummaryView(
            LocalDate from,
            LocalDate to,
            Money openingBalance,
            Money totalIncome,
            Money totalExpense,
            Money net,
            Money closingBalance,
            List<CategoryTotal> byCategory,
            List<MonthTotal> byMonth) {}
}
