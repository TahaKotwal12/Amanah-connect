package com.amanahconnect.lead;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    Page<Lead> findByStatus(LeadStatus status, Pageable pageable);

    long countByStatus(LeadStatus status);

    /** Optional status filter and optional lower-cased LIKE pattern over name, email and community name. */
    @Query(
            value =
                    "SELECT l FROM Lead l WHERE (:status IS NULL OR l.status = :status)"
                            + " AND (:pattern IS NULL OR lower(l.name) LIKE :pattern ESCAPE '\\'"
                            + " OR lower(cast(l.email AS string)) LIKE :pattern ESCAPE '\\'"
                            + " OR lower(coalesce(l.communityName, '')) LIKE :pattern ESCAPE '\\')",
            countQuery =
                    "SELECT count(l) FROM Lead l WHERE (:status IS NULL OR l.status = :status)"
                            + " AND (:pattern IS NULL OR lower(l.name) LIKE :pattern ESCAPE '\\'"
                            + " OR lower(cast(l.email AS string)) LIKE :pattern ESCAPE '\\'"
                            + " OR lower(coalesce(l.communityName, '')) LIKE :pattern ESCAPE '\\')")
    Page<Lead> search(@Param("status") LeadStatus status, @Param("pattern") String pattern, Pageable pageable);
}
