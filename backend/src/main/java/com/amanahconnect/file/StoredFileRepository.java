package com.amanahconnect.file;

import com.amanahconnect.tenant.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends TenantRepository<StoredFile, UUID> {

    Optional<StoredFile> findByCommunityIdAndObjectKeyAndDeletedAtIsNull(UUID communityId, String objectKey);

    /** Bytes of live files the community has. */
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.communityId = :communityId and f.deletedAt is null")
    long sumLiveBytesByCommunityId(@Param("communityId") UUID communityId);

    long countByCommunityIdAndDeletedAtIsNull(UUID communityId);
}
