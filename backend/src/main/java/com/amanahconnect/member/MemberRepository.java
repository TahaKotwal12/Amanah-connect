package com.amanahconnect.member;

import com.amanahconnect.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MemberRepository extends TenantRepository<Member, UUID> {

    Optional<Member> findByCommunityIdAndMemberNo(UUID communityId, String memberNo);

    boolean existsByCommunityIdAndMemberNo(UUID communityId, String memberNo);

    Page<Member> findByCommunityIdAndStatus(UUID communityId, MemberStatus status, Pageable pageable);

    long countByCommunityIdAndDeletedAtIsNull(UUID communityId);
}
