package com.amanahconnect.support;

import com.amanahconnect.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;

public interface SupportMessageRepository extends TenantRepository<SupportMessage, UUID> {

    List<SupportMessage> findByCommunityIdAndThreadIdOrderByCreatedAtAsc(UUID communityId, UUID threadId);

    boolean existsByCommunityIdAndAttachmentKey(UUID communityId, String attachmentKey);

    long countByCommunityIdAndThreadIdAndSenderSideAndReadAtIsNull(UUID communityId, UUID threadId, SupportSide senderSide);

    /** Marks everything the other side wrote in the thread, up to a seq, as read. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update SupportMessage m set m.readAt = :now where m.communityId = :communityId and m.thread.id = :threadId and m.senderSide = :side and m.readAt is null and m.seq <= :upToSeq")
    int markReadByCommunityId(@org.springframework.data.repository.query.Param("communityId") UUID communityId, @org.springframework.data.repository.query.Param("threadId") UUID threadId,
                              @org.springframework.data.repository.query.Param("side") SupportSide side, @org.springframework.data.repository.query.Param("upToSeq") long upToSeq,
                              @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
