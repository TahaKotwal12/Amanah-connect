package com.amanahconnect.community;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Communities are the tenant roots, so this is the one tenant-related repository that is not scoped. */
public interface CommunityRepository extends JpaRepository<Community, UUID> {

    Optional<Community> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
