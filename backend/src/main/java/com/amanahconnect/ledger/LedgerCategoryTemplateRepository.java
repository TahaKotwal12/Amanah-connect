package com.amanahconnect.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerCategoryTemplateRepository extends JpaRepository<LedgerCategoryTemplate, UUID> {

    List<LedgerCategoryTemplate> findAllByOrderBySortOrderAsc();
}
