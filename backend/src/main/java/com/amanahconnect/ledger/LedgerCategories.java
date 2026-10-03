package com.amanahconnect.ledger;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finding (and, if somebody removed it, restoring) the categories the system posts to. */
@Service
@Transactional
public class LedgerCategories {

    private final LedgerCategoryRepository categories;

    public LedgerCategories(LedgerCategoryRepository categories) {
        this.categories = categories;
    }

    /**
     * The community's category for this key. Communities get all of them when created; if one is somehow missing (an
     * older community, a renamed duplicate) it is adopted by its default name or created, so a payment never fails for it.
     */
    public LedgerCategory systemCategory(UUID communityId, String systemKey) {
        return categories.findByCommunityIdAndSystemKey(communityId, systemKey).orElseGet(() -> {
            String name = SystemCategories.DEFAULT_NAMES.get(systemKey);
            if (name == null) {
                throw new IllegalArgumentException("Unknown system category " + systemKey);
            }
            LedgerCategory category = categories.findByCommunityIdAndNameAndType(communityId, name, LedgerType.INCOME).orElseGet(() -> {
                LedgerCategory created = new LedgerCategory();
                created.setCommunityId(communityId);
                created.setName(name);
                created.setType(LedgerType.INCOME);
                return created;
            });
            category.setSystemKey(systemKey);
            category.setActive(true);
            return categories.save(category);
        });
    }
}
