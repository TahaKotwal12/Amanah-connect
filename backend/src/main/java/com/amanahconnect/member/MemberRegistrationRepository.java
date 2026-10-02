package com.amanahconnect.member;

import com.amanahconnect.tenant.TenantRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MemberRegistrationRepository extends TenantRepository<MemberRegistration, UUID> {

    Page<MemberRegistration> findByCommunityIdAndStatus(
            UUID communityId, RegistrationStatus status, Pageable pageable);
}
