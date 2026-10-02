package com.amanahconnect.community;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Communities are the tenant roots, so this is the one tenant-related repository that is not scoped. */
public interface CommunityRepository extends JpaRepository<Community, UUID> {

    Optional<Community> findBySlug(String slug);

    boolean existsBySlug(String slug);

    long countByPlanId(UUID planId);

    /** Communities whose owner is this user (used when an owner accepts an invitation). */
    @org.springframework.data.jpa.repository.Query("select c from Community c where c.owner.id = :ownerId and c.status = :status")
    java.util.List<Community> findByOwnerIdAndStatus(
            @org.springframework.data.repository.query.Param("ownerId") UUID ownerId,
            @org.springframework.data.repository.query.Param("status") CommunityStatus status);
}
