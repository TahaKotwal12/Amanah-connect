package com.amanahconnect.tenant;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What services use to act for the current community: the tenant id, a write check, and the one way
 * to turn "not found" into a 404.
 */
@Component
public class TenantGuard {

    public CurrentTenant tenant() {
        return TenantContext.require();
    }

    public UUID communityId() {
        return TenantContext.require().communityId();
    }

    /** Defence in depth for services; the request filter already blocks writes to suspended communities. */
    public void requireWritable() {
        CurrentTenant tenant = TenantContext.require();
        if (!tenant.writable()) {
            throw new ApiException(
                    ErrorCode.COMMUNITY_SUSPENDED,
                    "This community is " + tenant.status().toLowerCase() + " and is read-only.");
        }
    }

    /**
     * Unwraps a community-scoped lookup. A miss, whether the id does not exist or belongs to another
     * community, is the same 404 with the same body.
     */
    public <T> T found(Optional<T> resource) {
        return resource.orElseThrow(NotFoundException::new);
    }
}
