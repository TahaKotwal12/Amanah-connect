package com.amanahconnect.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * Base for repositories of tenant-owned entities (those with a non-null {@code community_id}).
 *
 * <p>It is deliberately not a {@code JpaRepository}: there is no {@code findById(id)},
 * {@code findAll()} or {@code delete...} to call by mistake. Reads are always scoped by
 * {@code communityId}, and a miss for another tenant's id is indistinguishable from "not found"
 * (callers answer 404). Financial rows are never deleted, so no delete method is offered.
 */
@NoRepositoryBean
public interface TenantRepository<T, ID> extends Repository<T, ID> {

    <S extends T> S save(S entity);

    Optional<T> findByIdAndCommunityId(ID id, UUID communityId);

    Page<T> findByCommunityId(UUID communityId, Pageable pageable);

    long countByCommunityId(UUID communityId);
}
