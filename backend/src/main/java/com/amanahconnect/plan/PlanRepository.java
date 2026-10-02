package com.amanahconnect.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    Optional<Plan> findByCode(String code);

    boolean existsByCode(String code);

    List<Plan> findByPublicPlanTrueAndActiveTrueOrderBySortOrderAsc();
}
