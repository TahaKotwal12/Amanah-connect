package com.amanahconnect.plan.admin;

import com.amanahconnect.common.money.Money;
import com.amanahconnect.common.money.MoneyAmount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public final class AdminPlanDtos {

    private AdminPlanDtos() {}

    public record CreatePlanRequest(
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Z0-9_]+$", message = "must be upper case letters, digits and underscores") String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull @MoneyAmount(nonNegative = true) BigDecimal priceMonthly,
            @NotNull @MoneyAmount(nonNegative = true) BigDecimal priceYearly,
            Map<String, Object> limits,
            Map<String, Object> features,
            Boolean publicPlan,
            Boolean active,
            @Min(0) @Max(100000) Integer sortOrder) {}

    /** Partial update: a null field is left unchanged. The code never changes. */
    public record UpdatePlanRequest(
            @Size(min = 1, max = 100) String name,
            @MoneyAmount(nonNegative = true) BigDecimal priceMonthly,
            @MoneyAmount(nonNegative = true) BigDecimal priceYearly,
            Map<String, Object> limits,
            Map<String, Object> features,
            Boolean publicPlan,
            Boolean active,
            @Min(0) @Max(100000) Integer sortOrder) {}

    public record PlanView(
            UUID id,
            String code,
            String name,
            Money priceMonthly,
            Money priceYearly,
            Map<String, Object> limits,
            Map<String, Object> features,
            boolean publicPlan,
            boolean active,
            int sortOrder,
            long communityCount,
            Instant createdAt) {}

    // ---- subscriptions ----------------------------------------------------------------------------

    public record RecordSubscriptionRequest(
            @NotNull UUID communityId,
            @NotNull UUID planId,
            @NotNull @MoneyAmount(nonNegative = true) BigDecimal amount,
            @Size(max = 100) String reference,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            /** The day the community paid; defaults to today (IST). */
            LocalDate paidOn) {}

    public record CancelSubscriptionRequest(@NotBlank @Size(max = 500) String reason) {}

    public record SubscriptionView(
            UUID id,
            UUID communityId,
            String communityName,
            String communitySlug,
            String planCode,
            String planName,
            LocalDate periodStart,
            LocalDate periodEnd,
            Money amount,
            LocalDate paidOn,
            String reference,
            /** ACTIVE, EXPIRING, EXPIRED or CANCELLED (derived from the period and today's date). */
            String status,
            String cancelReason,
            long daysLeft,
            Instant createdAt) {}
}
