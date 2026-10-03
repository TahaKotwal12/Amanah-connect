package com.amanahconnect.member;

import com.amanahconnect.tenant.CrossTenantLookup;
import com.amanahconnect.tenant.TenantRepository;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberInviteRepository extends TenantRepository<MemberInvite, UUID> {

    /** Public invite pages arrive with only a token, so this lookup is how the tenant is discovered. */
    @CrossTenantLookup("A public invite link carries only a token; the community is discovered from it.")
    Optional<MemberInvite> findByTokenHash(String tokenHash);

    /** Same, row-locked, so concurrent registrations through one link cannot exceed its maximum uses. */
    @CrossTenantLookup("A public invite link carries only a token; the community is discovered from it.")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberInvite> findWithLockByTokenHash(String tokenHash);

    /** @param state ALL, ACTIVE, EXPIRED, USED_UP or REVOKED (REVOKED wins over EXPIRED wins over USED_UP) */
    @Query(
            value =
                    "SELECT i FROM MemberInvite i WHERE i.communityId = :communityId AND (:state = 'ALL'"
                            + " OR (:state = 'REVOKED' AND i.revokedAt IS NOT NULL)"
                            + " OR (:state = 'EXPIRED' AND i.revokedAt IS NULL AND i.expiresAt <= :now)"
                            + " OR (:state = 'USED_UP' AND i.revokedAt IS NULL AND i.expiresAt > :now AND i.usedCount >= i.maxUses)"
                            + " OR (:state = 'ACTIVE' AND i.revokedAt IS NULL AND i.expiresAt > :now AND i.usedCount < i.maxUses))",
            countQuery =
                    "SELECT count(i) FROM MemberInvite i WHERE i.communityId = :communityId AND (:state = 'ALL'"
                            + " OR (:state = 'REVOKED' AND i.revokedAt IS NOT NULL)"
                            + " OR (:state = 'EXPIRED' AND i.revokedAt IS NULL AND i.expiresAt <= :now)"
                            + " OR (:state = 'USED_UP' AND i.revokedAt IS NULL AND i.expiresAt > :now AND i.usedCount >= i.maxUses)"
                            + " OR (:state = 'ACTIVE' AND i.revokedAt IS NULL AND i.expiresAt > :now AND i.usedCount < i.maxUses))")
    Page<MemberInvite> searchByCommunityId(@Param("communityId") UUID communityId, @Param("state") String state, @Param("now") Instant now, Pageable pageable);
}
