package com.amanahconnect.community.admin;

import com.amanahconnect.common.money.Money;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the super-admin community endpoints. */
public final class AdminCommunityDtos {

    private AdminCommunityDtos() {}

    static final String PHONE = "^[0-9+()\\-\\s.]{3,30}$";
    static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";

    public record CreateCommunityRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 80) @Pattern(regexp = SLUG, message = "must be lower case letters, digits and hyphens") String slug,
            @Size(max = 150) String contactName,
            @Email @Size(max = 254) String contactEmail,
            @Pattern(regexp = PHONE, message = "is not a valid phone number") String contactPhone,
            @Size(max = 200) String addressLine1,
            @Size(max = 200) String addressLine2,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter country code") String country,
            @Past LocalDate dateOfEstablishment,
            @NotNull UUID planId,
            @Min(1) @Max(12) Integer financialYearStartMonth,
            @NotBlank @Size(max = 150) String ownerName,
            @NotBlank @Email @Size(max = 254) String ownerEmail,
            UUID leadId) {}

    /** Partial update: a null field is left unchanged. */
    public record UpdateCommunityRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 150) String contactName,
            @Email @Size(max = 254) String contactEmail,
            @Pattern(regexp = PHONE, message = "is not a valid phone number") String contactPhone,
            @Size(max = 200) String addressLine1,
            @Size(max = 200) String addressLine2,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter country code") String country,
            @Past LocalDate dateOfEstablishment,
            UUID planId,
            Boolean require2fa,
            /** If given, the update is refused with 409 when the community changed since this version was read. */
            Long version) {}

    public record StatusChangeRequest(@NotBlank @Size(max = 500) String reason) {}

    public record OptionalReasonRequest(@Size(max = 500) String reason) {}

    public record ResetAdminPasswordRequest(UUID userId) {}

    public record PlanRef(UUID id, String code, String name) {}

    public record OwnerView(UUID id, String fullName, String email, String status) {}

    public record CommunityView(
            UUID id,
            String name,
            String slug,
            String status,
            String statusReason,
            Instant statusChangedAt,
            String contactName,
            String contactEmail,
            String contactPhone,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String postalCode,
            String country,
            LocalDate dateOfEstablishment,
            PlanRef plan,
            String currency,
            int financialYearStartMonth,
            boolean require2fa,
            OwnerView owner,
            long memberCount,
            LocalDate subscriptionEndsOn,
            String subscriptionStatus,
            long version,
            Instant createdAt) {}

    public record CommunityListItem(
            UUID id,
            String name,
            String slug,
            String status,
            PlanRef plan,
            long memberCount,
            LocalDate subscriptionEndsOn,
            String subscriptionStatus,
            String ownerName,
            String ownerEmail,
            Instant createdAt) {}

    public record SubscriptionLine(
            UUID id, String planCode, String planName, LocalDate periodStart, LocalDate periodEnd, Money amount, LocalDate paidOn, String reference, String status) {}

    public record CommunityExport(
            Instant exportedAt, CommunityView profile, long membersCount, List<SubscriptionLine> subscriptionHistory) {}

    public record ActivityLine(UUID id, String action, String entityType, UUID entityId, UUID actorUserId, String actorName, Instant at) {}

    public record Counts(long membersActive, long membersInactive, long pendingRegistrations, long openInvoices, long openComplaints, long openSupportThreads) {}

    public record FinanceSummary(
            Money invoicedOpen, Money collectedOnOpenAndPaid, Money outstanding, Money incomeThisFinancialYear, Money expenseThisFinancialYear, Money netThisFinancialYear, LocalDate financialYearStart) {}

    public record CommunityOverview(CommunityView community, Counts counts, FinanceSummary finance, List<ActivityLine> recentActivity) {}
}
