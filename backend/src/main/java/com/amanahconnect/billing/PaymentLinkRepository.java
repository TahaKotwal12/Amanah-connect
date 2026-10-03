package com.amanahconnect.billing;

import com.amanahconnect.tenant.CrossTenantLookup;
import com.amanahconnect.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;

public interface PaymentLinkRepository extends TenantRepository<PaymentLink, UUID> {

    /** The public payment page arrives with only a token, so this lookup is how the community is discovered. */
    @CrossTenantLookup("A public pay link carries only a token; the community and invoice are discovered from it.")
    Optional<PaymentLink> findByTokenHash(String tokenHash);
}
