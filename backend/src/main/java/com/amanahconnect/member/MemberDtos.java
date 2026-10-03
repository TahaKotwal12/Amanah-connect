package com.amanahconnect.member;

import com.amanahconnect.common.money.Money;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public final class MemberDtos {

    public static final String PHONE = "^[0-9+()\\-. ]{5,30}$";

    private MemberDtos() {}

    public record MemberView(
            UUID id,
            String memberNo,
            String fullName,
            String email,
            String phone,
            String group,
            MemberStatus status,
            String statusReason,
            Instant statusChangedAt,
            LocalDate joinedOn,
            Map<String, Object> customFields,
            boolean consentEmail,
            Instant createdAt,
            Instant updatedAt) {

        static MemberView of(Member m) {
            return new MemberView(m.getId(), m.getMemberNo(), m.getFullName(), m.getEmail(), m.getPhone(), m.getGroupLabel(), m.getStatus(),
                    m.getStatusReason(), m.getStatusChangedAt(), m.getJoinedOn(), m.getCustomFields(), m.isConsentEmail(), m.getCreatedAt(), m.getUpdatedAt());
        }
    }

    /** The member number is generated, never supplied. */
    public record CreateMemberRequest(
            @NotBlank @Size(max = 150) String fullName,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = PHONE, message = "is not a valid phone number") String phone,
            @Size(max = 100) String group,
            @PastOrPresent LocalDate joinedOn,
            Map<String, Object> customFields,
            Boolean consentEmail,
            /** Several members may deliberately share one address (a household); say so explicitly. */
            Boolean allowDuplicateEmail) {}

    /** Partial: a missing (null) field is unchanged; an empty string clears email, phone and group. */
    public record UpdateMemberRequest(
            @Size(min = 1, max = 150) String fullName,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^$|" + PHONE, message = "is not a valid phone number") String phone,
            @Size(max = 100) String group,
            @PastOrPresent LocalDate joinedOn,
            Map<String, Object> customFields,
            Boolean consentEmail,
            Boolean allowDuplicateEmail) {}

    public record DeactivateRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ActivateRequest(@Size(max = 500) String reason) {}

    public record MemberCounts(long total, long active, long inactive, long newThisMonth, long pendingRegistrations) {}

    /** What blocks a deletion. */
    public record UnpaidSummary(long invoices, Money outstanding) {}

    public record MemberFilter(String q, MemberStatus status, String group) {}
}
