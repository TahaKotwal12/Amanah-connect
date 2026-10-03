package com.amanahconnect.community.settings;

import jakarta.validation.Valid;
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
import java.util.Map;

public final class SettingsDtos {

    static final String PHONE = "^[0-9+()\\-. ]{5,30}$";

    private SettingsDtos() {}

    public record NotificationSettingsView(int dueReminderDaysBefore, int overdueReminderEveryDays, boolean sendWelcomeEmail, boolean sendReceiptEmail) {}

    public record SettingsView(
            String name,
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
            String logoKey,
            /** A short-lived signed URL, or null when there is no logo (or storage is unavailable). */
            String logoUrl,
            String currency,
            int financialYearStartMonth,
            /** True once invoices, payments or ledger entries exist: currency and financial year can no longer change. */
            boolean financialSettingsLocked,
            String upiId,
            String upiPayeeName,
            /** What this community calls a member's group: "Flat", "Family", "Batch" ... */
            String memberGroupLabel,
            NotificationSettingsView notifications,
            long version) {}

    /** Partial: a missing (null) field is unchanged; an empty string clears an optional text field. */
    public record UpdateNotificationSettings(
            @Min(0) @Max(60) Integer dueReminderDaysBefore,
            @Min(1) @Max(90) Integer overdueReminderEveryDays,
            Boolean sendWelcomeEmail,
            Boolean sendReceiptEmail) {}

    public record UpdateSettingsRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 150) String contactName,
            @Email @Size(max = 254) String contactEmail,
            @Pattern(regexp = "^$|" + PHONE, message = "is not a valid phone number") String contactPhone,
            @Size(max = 200) String addressLine1,
            @Size(max = 200) String addressLine2,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter country code") String country,
            @Past LocalDate dateOfEstablishment,
            /** The key returned by the upload-url call, after the file was uploaded. */
            @Size(max = 255) String logoKey,
            @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter currency code") String currency,
            @Min(1) @Max(12) Integer financialYearStartMonth,
            @Size(max = 100) String upiId,
            @Size(max = 150) String upiPayeeName,
            @Size(max = 30) String memberGroupLabel,
            @Valid UpdateNotificationSettings notifications,
            /** Optional: the version you last read; a stale one is refused with 409. */
            Long version) {}

    public record LogoUploadRequest(@NotBlank @Size(max = 100) String contentType, @NotNull @Min(1) Long sizeBytes) {}

    public record LogoUploadView(String uploadUrl, String method, Map<String, String> headers, String logoKey, Instant expiresAt, long maxBytes) {}
}
