package com.amanahconnect.member;

import com.amanahconnect.tenant.TenantRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;

public interface MemberRegistrationRepository extends TenantRepository<MemberRegistration, UUID> {

    Page<MemberRegistration> findByCommunityIdAndStatus(UUID communityId, RegistrationStatus status, Pageable pageable);

    /** Row-locked, so two admins approving the same registration are serialised and the second sees it decided. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberRegistration> findWithLockByIdAndCommunityId(UUID id, UUID communityId);

    boolean existsByCommunityIdAndEmailAndStatus(UUID communityId, String email, RegistrationStatus status);

    long countByCommunityIdAndStatus(UUID communityId, RegistrationStatus status);
}
