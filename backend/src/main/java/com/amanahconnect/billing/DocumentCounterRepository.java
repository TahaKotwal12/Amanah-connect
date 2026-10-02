package com.amanahconnect.billing;

import com.amanahconnect.tenant.TenantRepository;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentCounterRepository extends TenantRepository<DocumentCounter, UUID> {

    /** Creates the counter row if it does not exist yet; concurrent callers simply no-op. */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO document_counters (id, community_id, counter_type, financial_year, last_value)
                    VALUES (gen_random_uuid(), :communityId, :counterType, :financialYear, 0)
                    ON CONFLICT (community_id, counter_type, financial_year) DO NOTHING
                    """,
            nativeQuery = true)
    void ensureExists(
            @Param("communityId") UUID communityId,
            @Param("counterType") String counterType,
            @Param("financialYear") String financialYear);

    /** SELECT ... FOR UPDATE: concurrent issuers queue here until the holder commits or rolls back. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentCounter> findByCommunityIdAndCounterTypeAndFinancialYear(
            UUID communityId, CounterType counterType, String financialYear);
}
