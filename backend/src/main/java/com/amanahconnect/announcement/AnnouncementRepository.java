package com.amanahconnect.announcement;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Only community-scoped reads plus save: announcements are managed, not deleted, by their status. */
public interface AnnouncementRepository extends Repository<Announcement, UUID> {

    <S extends Announcement> S save(S announcement);

    Optional<Announcement> findByIdAndCommunityId(UUID id, UUID communityId);

    Page<Announcement> findByCommunityId(UUID communityId, Pageable pageable);

    /** What a community admin sees: the community's own announcements plus platform-wide ones. */
    @Query("select a from Announcement a where a.communityId = :communityId or a.communityId is null")
    Page<Announcement> findVisibleToCommunity(@Param("communityId") UUID communityId, Pageable pageable);

    /** Platform-wide announcements (super admin). */
    Page<Announcement> findByCommunityIdIsNull(Pageable pageable);
}
