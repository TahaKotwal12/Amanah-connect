package com.amanahconnect.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.tenant.TenantRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Version;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.repository.support.Repositories;
import org.springframework.beans.factory.annotation.Autowired;

/** Tenancy and persistence conventions from CLAUDE.md, enforced across every entity. */
class RepositoryConventionsIT extends AbstractIntegrationTest {

    @Autowired EntityManagerFactory emf;
    @Autowired ListableBeanFactory beans;

    @Test
    void everyTenantEntityIsAccessedOnlyThroughATenantRepository() {
        Repositories repositories = new Repositories(beans);
        List<String> problems = new ArrayList<>();
        int tenantEntities = 0;
        for (EntityType<?> type : emf.getMetamodel().getEntities()) {
            Class<?> entity = type.getJavaType();
            if (!hasMandatoryCommunityId(entity)) {
                continue;
            }
            tenantEntities++;
            Class<?> repository =
                    repositories.getRepositoryInformationFor(entity)
                            .map(info -> info.getRepositoryInterface())
                            .orElse(null);
            if (repository == null) {
                problems.add(entity.getSimpleName() + " has no repository");
            } else if (!TenantRepository.class.isAssignableFrom(repository)) {
                problems.add(repository.getSimpleName() + " must extend TenantRepository");
            }
        }

        assertThat(problems).isEmpty();
        assertThat(tenantEntities).as("tenant entities found").isGreaterThanOrEqualTo(15);
    }

    @Test
    void tenantRepositoriesOfferNoUnscopedLookupOrDelete() {
        Set<String> forbidden = Set.of("findById", "findAll", "getById", "getReferenceById", "delete", "deleteById", "deleteAll", "existsById");
        List<String> problems = new ArrayList<>();
        Repositories repositories = new Repositories(beans);
        for (EntityType<?> type : emf.getMetamodel().getEntities()) {
            repositories.getRepositoryInformationFor(type.getJavaType()).ifPresent(info -> {
                Class<?> repository = info.getRepositoryInterface();
                if (!TenantRepository.class.isAssignableFrom(repository)) {
                    return;
                }
                for (Method method : repository.getMethods()) {
                    if (forbidden.contains(method.getName())) {
                        problems.add(repository.getSimpleName() + "." + method.getName());
                    }
                }
            });
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void invoicesPaymentsLedgerEntriesAndCommunitiesUseOptimisticLocking() {
        for (String name : List.of("billing.Invoice", "billing.PaymentRecord", "ledger.LedgerEntry", "community.Community")) {
            Class<?> entity = emf.getMetamodel().getEntities().stream()
                    .map(EntityType::getJavaType)
                    .filter(c -> c.getName().equals("com.amanahconnect." + name))
                    .findFirst().orElseThrow();
            boolean versioned = false;
            for (Field field : entity.getDeclaredFields()) {
                versioned |= field.isAnnotationPresent(Version.class);
            }
            assertThat(versioned).as(name + " has @Version").isTrue();
        }
    }

    private static boolean hasMandatoryCommunityId(Class<?> entity) {
        return com.amanahconnect.tenant.TenantEntity.class.isAssignableFrom(entity);
    }
}
