package com.amanahconnect.mail;

import com.amanahconnect.mail.MailTemplate.Audience;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminEmailDtos {

    private AdminEmailDtos() {}

    public record TemplateView(String name, Audience audience, boolean sensitive, List<String> requiredFields, String sampleSubject) {}

    public record OutboxItem(UUID id, UUID communityId, String to, String template, String status, int attempts, Instant nextAttemptAt, Instant lastAttemptAt, Instant sentAt, String error, Instant createdAt) {}

    public record OutboxStats(long pending, long failed, long sentLast24h, Instant oldestPending, long suppressedAddresses) {}

    public record SuppressionView(UUID id, String email, String reason, String source, Instant createdAt) {}

    public record AddSuppressionRequest(@NotBlank @Email @Size(max = 320) String email) {}
}
