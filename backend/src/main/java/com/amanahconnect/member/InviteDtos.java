package com.amanahconnect.member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InviteDtos {

    private InviteDtos() {}

    /** Both limits have defaults: 14 days, 50 registrations. */
    public record CreateInviteRequest(@Min(1) @Max(90) Integer expiresInDays, @Min(1) @Max(1000) Integer maxUses, @Size(max = 100) String defaultGroup) {}

    /** Emails the link to one person; the link works once. */
    public record InviteByEmailRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @Size(max = 150) String name,
            @Min(1) @Max(90) Integer expiresInDays,
            @Size(max = 100) String defaultGroup) {}

    public record InviteView(
            UUID id, String state, Instant expiresAt, int maxUses, int usedCount, String defaultGroup, String invitedEmail, Instant revokedAt, Instant createdAt) {}

    /** The only time the link and its QR code exist: the token is stored hashed and cannot be shown again. */
    public record CreatedInviteView(InviteView invite, String link, String qrCodePngBase64) {}

    public record EmailedInviteView(InviteView invite) {}

    // ---- registrations ------------------------------------------------------------------------------------

    public record RegistrationView(
            UUID id,
            String fullName,
            String email,
            String phone,
            String group,
            boolean consentEmail,
            RegistrationStatus status,
            Instant createdAt,
            Instant reviewedAt,
            String rejectionReason,
            UUID memberId,
            String memberNo,
            /** For a pending one: the number of another member already using this address, if any. */
            String emailUsedByMemberNo) {}

    public record ApproveRegistrationRequest(Boolean allowDuplicateEmail, @Size(max = 100) String group) {}

    public record RejectRegistrationRequest(@NotBlank @Size(max = 500) String reason) {}

    // ---- public side ---------------------------------------------------------------------------------------

    public record FormField(String name, String label, String type, boolean required, Integer maxLength) {}

    /** What a visitor with a valid link may learn: the community's name and logo and the form to fill. Nothing else. */
    public record PublicInviteView(String communityName, String logoUrl, String groupLabel, String defaultGroup, List<FormField> fields) {}

    public record PublicRegisterRequest(
            @NotBlank @Size(max = 150) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = MemberDtos.PHONE, message = "is not a valid phone number") String phone,
            @Size(max = 100) String group,
            Boolean consentEmail,
            /** Honeypot: hidden from people, filled by bots. */
            String website) {}

    public record PublicRegisterResponse(boolean received) {}
}
