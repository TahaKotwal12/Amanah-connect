package com.amanahconnect.dataexport;

import com.amanahconnect.tenant.CrossTenantLookup;
import com.amanahconnect.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataExportRepository extends TenantRepository<DataExport, UUID> {

    List<DataExport> findByCommunityIdOrderByCreatedAtDesc(UUID communityId, Pageable pageable);

    long countByCommunityIdAndStatusIn(UUID communityId, java.util.Collection<ExportStatus> statuses);

    long countByCommunityIdAndCreatedAtGreaterThanEqual(UUID communityId, Instant since);

    /** The public download link resolves its token without knowing the community. */
    @CrossTenantLookup("a download link carries only its secret token; the community is found from it")
    Optional<DataExport> findByTokenHash(String tokenHash);

    /** For the worker: any community's export, to build or expire it. */
    @CrossTenantLookup("the export worker serves every community")
    @Query("select e from DataExport e where e.id = :id")
    Optional<DataExport> findForWorker(@Param("id") UUID id);
}
