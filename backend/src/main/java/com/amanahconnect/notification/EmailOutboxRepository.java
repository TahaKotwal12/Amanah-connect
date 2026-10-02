package com.amanahconnect.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Outbox access for the sender job (system-wide) and for the community's own queue views. */
public interface EmailOutboxRepository extends Repository<EmailOutbox, UUID> {

    <S extends EmailOutbox> S save(S email);

    List<EmailOutbox> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            EmailStatus status, Instant now);

    List<EmailOutbox> findByCommunityIdAndStatus(UUID communityId, EmailStatus status);

    /** Emails queued for a community since the given moment, not counting failed ones (the monthly quota). */
    long countByCommunityIdAndCreatedAtGreaterThanEqualAndStatusNot(UUID communityId, Instant since, EmailStatus status);
}
