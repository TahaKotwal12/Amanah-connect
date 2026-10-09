package com.amanahconnect.member;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.amanahconnect.tenant.TenantRepository;

public interface MemberRepository extends TenantRepository<Member, UUID> {

    Optional<Member> findByCommunityIdAndMemberNo(UUID communityId, String memberNo);

    boolean existsByCommunityIdAndMemberNo(UUID communityId, String memberNo);

    Page<Member> findByCommunityIdAndStatus(UUID communityId, MemberStatus status, Pageable pageable);

    long countByCommunityIdAndDeletedAtIsNull(UUID communityId);

    List<Member> findByCommunityIdAndStatusAndDeletedAtIsNull(UUID communityId, MemberStatus status);

    /** Members of one group, compared without regard to case. */
    @Query("SELECT m FROM Member m WHERE m.communityId = :communityId AND m.deletedAt IS NULL AND m.status = :status AND lower(m.groupLabel) = :group")
    List<Member> findGroupMembersByCommunityId(@Param("communityId") UUID communityId, @Param("status") MemberStatus status, @Param("group") String lowerCaseGroup);

    List<Member> findByCommunityIdAndIdInAndDeletedAtIsNull(UUID communityId, java.util.Collection<UUID> ids);

    long countByCommunityIdAndDeletedAtIsNullAndStatus(UUID communityId, MemberStatus status);

    long countByCommunityIdAndDeletedAtIsNullAndCreatedAtGreaterThanEqual(UUID communityId, Instant since);

    /** A live (not deleted) member of the community. Deleted members are gone as far as the API is concerned. */
    Optional<Member> findByIdAndCommunityIdAndDeletedAtIsNull(UUID id, UUID communityId);

    /** Another live member using this address (case-insensitive), ignoring {@code exceptId}. */
    Optional<Member> findFirstByCommunityIdAndEmailAndDeletedAtIsNullAndIdNot(UUID communityId, String email, UUID exceptId);

    /**
     * Search and filter a community's live members. {@code pattern} is a lower-cased LIKE pattern over name, member
     * number, email and phone; {@code digits} a LIKE pattern over the phone's digits only; {@code group} is lower-cased.
     */
    @Query(
            value =
                    "SELECT m FROM Member m WHERE m.communityId = :communityId AND m.deletedAt IS NULL"
                            + " AND (:status IS NULL OR m.status = :status)"
                            + " AND (:group IS NULL OR lower(m.groupLabel) = :group)"
                            + " AND (:pattern IS NULL OR lower(m.fullName) LIKE :pattern ESCAPE '\\'"
                            + "   OR lower(m.memberNo) LIKE :pattern ESCAPE '\\'"
                            + "   OR lower(cast(m.email AS string)) LIKE :pattern ESCAPE '\\'"
                            + "   OR lower(coalesce(m.phone, '')) LIKE :pattern ESCAPE '\\'"
                            + "   OR (cast(:digits AS string) IS NOT NULL AND cast(function('regexp_replace', coalesce(m.phone, ''), '[^0-9]', '', 'g') AS string) LIKE cast(:digits AS string)))",
            countQuery =
                    "SELECT count(m) FROM Member m WHERE m.communityId = :communityId AND m.deletedAt IS NULL"
                            + " AND (:status IS NULL OR m.status = :status)"
                            + " AND (:group IS NULL OR lower(m.groupLabel) = :group)"
                            + " AND (:pattern IS NULL OR lower(m.fullName) LIKE :pattern ESCAPE '\\'"
                            + "   OR lower(m.memberNo) LIKE :pattern ESCAPE '\\'"
                            + "   OR lower(cast(m.email AS string)) LIKE :pattern ESCAPE '\\'"
                            + "   OR lower(coalesce(m.phone, '')) LIKE :pattern ESCAPE '\\'"
                            + "   OR (cast(:digits AS string) IS NOT NULL AND cast(function('regexp_replace', coalesce(m.phone, ''), '[^0-9]', '', 'g') AS string) LIKE cast(:digits AS string)))")
    Page<Member> searchByCommunityId(
            @Param("communityId") UUID communityId,
            @Param("status") MemberStatus status,
            @Param("group") String group,
            @Param("pattern") String pattern,
            @Param("digits") String digits,
            Pageable pageable);

    /** For erasure: the member, deleted or not, locked so two requests cannot both erase. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<Member> findWithLockByIdAndCommunityId(UUID id, UUID communityId);
}
