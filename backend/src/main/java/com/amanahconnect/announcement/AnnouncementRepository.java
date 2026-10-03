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

    Page<Announcement> findByCommunityIdAndStatus(UUID communityId, AnnouncementStatus status, Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Announcement> findWithLockByIdAndCommunityId(UUID id, UUID communityId);

    @Query("select a from Announcement a where a.id = :id and a.communityId is null")
    Optional<Announcement> findPlatformById(@Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Announcement a where a.id = :id and a.communityId is null")
    Optional<Announcement> findPlatformWithLockById(@Param("id") UUID id);

    @Query("select a from Announcement a where a.communityId is null and a.status = :status")
    Page<Announcement> findPlatformByStatus(@Param("status") AnnouncementStatus status, Pageable pageable);

    /** Scheduled announcements, of any community or the platform, whose time has come, oldest first. */
    @Query("select a.id from Announcement a where a.status = com.amanahconnect.announcement.AnnouncementStatus.SCHEDULED and a.scheduledAt <= :now order by a.scheduledAt, a.id")
    java.util.List<UUID> findDueIds(@Param("now") java.time.Instant now);

    /** For the job only: it locks the row and re-checks the state, whichever community or the platform it belongs to. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Announcement a where a.id = :id")
    Optional<Announcement> findWithLockForDispatch(@Param("id") UUID id);
}
