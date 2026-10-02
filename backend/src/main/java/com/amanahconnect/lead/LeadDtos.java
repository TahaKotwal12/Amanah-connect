package com.amanahconnect.lead;

import com.amanahconnect.community.admin.AdminCommunityDtos.CreateCommunityRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class LeadDtos {

    private LeadDtos() {}

    /** The public "request a demo" form. {@code website} is the honeypot: real visitors never see or fill it. */
    public record PublicLeadRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = "^[0-9+()\\-. ]{5,30}$", message = "is not a valid phone number") String phone,
            @Size(max = 200) String communityName,
            @Min(1) @Max(10_000_000) Integer size,
            @Size(max = 2000) String message,
            String website) {}

    public record PublicLeadResponse(boolean received) {}

    public record LeadView(
            UUID id,
            String name,
            String email,
            String phone,
            String communityName,
            Integer size,
            String message,
            LeadStatus status,
            UUID handledBy,
            UUID convertedCommunityId,
            String source,
            Instant createdAt,
            Instant updatedAt) {

        public static LeadView of(Lead lead) {
            return new LeadView(
                    lead.getId(), lead.getName(), lead.getEmail(), lead.getPhone(), lead.getCommunityName(), lead.getSizeEstimate(),
                    lead.getMessage(), lead.getStatus(), lead.getHandledBy(), lead.getConvertedCommunityId(), lead.getSource(),
                    lead.getCreatedAt(), lead.getUpdatedAt());
        }
    }

    /** CONVERTED is not settable here: it happens only by creating the community from the lead. */
    public record UpdateLeadStatusRequest(@NotNull LeadStatus status) {}

    /** A create-community payload pre-filled from the lead. The plan is a suggestion; the admin confirms it. */
    public record CommunityDraft(CreateCommunityRequest request, String suggestedPlanCode) {}
}
