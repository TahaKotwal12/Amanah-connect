package com.amanahconnect.member;

import com.amanahconnect.tenant.CrossTenantLookup;
import com.amanahconnect.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;

public interface MemberInviteRepository extends TenantRepository<MemberInvite, UUID> {

    /** Public invite pages arrive with only a token, so this lookup is how the tenant is discovered. */
    @CrossTenantLookup("A public invite link carries only a token; the community is discovered from it.")
    Optional<MemberInvite> findByTokenHash(String tokenHash);
}
