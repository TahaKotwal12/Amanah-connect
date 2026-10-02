package com.amanahconnect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.archfixtures.BadFixtures;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Proves the architecture rules have teeth: each one must FAIL on the bad fixtures and PASS on the good
 * ones. Without this, a typo in a rule would turn it into a check that can never fail.
 */
class ArchitectureRulesSelfTest {

    private static final JavaClasses BAD =
            new ClassFileImporter().importClasses(
                    BadFixtures.BadController.class, BadFixtures.BodyWithCommunityId.class, BadFixtures.BadTenantRepository.class, BadFixtures.MoneyHolder.class);
    private static final JavaClasses GOOD =
            new ClassFileImporter().importClasses(
                    BadFixtures.GoodController.class, BadFixtures.GoodBody.class, BadFixtures.View.class, BadFixtures.GoodTenantRepository.class);

    private static void assertFails(ArchRule rule, String... expectedFragments) {
        assertThatThrownBy(() -> rule.check(BAD)).isInstanceOf(AssertionError.class).satisfies(e -> {
            for (String fragment : expectedFragments) {
                assertThat(e.getMessage()).contains(fragment);
            }
        });
    }

    @Test
    void controllersMayNotTouchRepositoriesOrTheEntityManager() {
        assertFails(ArchitectureRules.CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES, "MemberRepository", "EntityManager");
        assertThatCode(() -> ArchitectureRules.CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES.check(GOOD)).doesNotThrowAnyException();
    }

    @Test
    void controllersMayNotReturnOrAcceptEntitiesEvenInsideGenerics() {
        assertFails(ArchitectureRules.CONTROLLERS_DO_NOT_EXPOSE_ENTITIES, "returns entity", "leaksAnEntity", "leaksEntitiesInsideGenerics", "accepts entity");
        assertThatCode(() -> ArchitectureRules.CONTROLLERS_DO_NOT_EXPOSE_ENTITIES.check(GOOD)).doesNotThrowAnyException();
    }

    @Test
    void tenantRepositoriesMayNotHaveUnscopedLookupsOrDeletes() {
        assertFails(ArchitectureRules.TENANT_REPOSITORIES_HAVE_NO_UNSCOPED_LOOKUPS,
                "findById", "findByFullName", "countByStatus", "deleteByMemberNo");
        assertThatCode(() -> ArchitectureRules.TENANT_REPOSITORIES_HAVE_NO_UNSCOPED_LOOKUPS.check(GOOD)).as("scoped, or marked @CrossTenantLookup").doesNotThrowAnyException();
    }

    @Test
    void mutatingEndpointsNeedAuditedOrAuditHandledBy() {
        assertFails(ArchitectureRules.MUTATING_CONTROLLER_METHODS_ARE_AUDITED, "mutatesWithoutAudit", "deletesWithCommunityIdInPath", "acceptsAnEntity");
        assertThatCode(() -> ArchitectureRules.MUTATING_CONTROLLER_METHODS_ARE_AUDITED.check(GOOD)).doesNotThrowAnyException();
    }

    @Test
    void communityEndpointsMayNotTakeACommunityIdFromTheClient() {
        assertFails(ArchitectureRules.COMMUNITY_CONTROLLERS_TAKE_NO_COMMUNITY_ID_FROM_THE_CLIENT,
                "community id in its path", "takes a community id from the request", "carries communityId");
        assertThatCode(() -> ArchitectureRules.COMMUNITY_CONTROLLERS_TAKE_NO_COMMUNITY_ID_FROM_THE_CLIENT.check(GOOD)).doesNotThrowAnyException();
    }

    @Test
    void noFloatingPointFieldsAnywhere() {
        assertFails(ArchitectureRules.NO_FLOATING_POINT_FIELDS, "amount", "rate");
        assertThatCode(() -> ArchitectureRules.NO_FLOATING_POINT_FIELDS.check(GOOD)).doesNotThrowAnyException();
    }
}
