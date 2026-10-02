package com.amanahconnect;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.amanahconnect", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule noFieldInjection =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.beans.factory.annotation.Autowired")
                    .because("use constructor injection");

    @ArchTest
    static final ArchRule commonDoesNotDependOnFeatureModules =
            noClasses()
                    .that()
                    .resideInAPackage("com.amanahconnect.common..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.amanahconnect.auth..",
                            "com.amanahconnect.tenant..",
                            "com.amanahconnect.community..",
                            "com.amanahconnect.plan..",
                            "com.amanahconnect.member..",
                            "com.amanahconnect.billing..",
                            "com.amanahconnect.ledger..",
                            "com.amanahconnect.complaint..",
                            "com.amanahconnect.support..",
                            "com.amanahconnect.announcement..",
                            "com.amanahconnect.notification..",
                            "com.amanahconnect.audit..",
                            "com.amanahconnect.file..",
                            "com.amanahconnect.lead..",
                            "com.amanahconnect.report..",
                            "com.amanahconnect.publicapi..")
                    .because("common is the base layer");

    @ArchTest
    static final ArchRule securityCodeNeverMatchesOnTheRawRequestUri =
            noClasses()
                    .should()
                    .callMethod(jakarta.servlet.http.HttpServletRequest.class, "getRequestURI")
                    .because("the raw URI is still percent-encoded and can be used to slip past path-based filters; "
                            + "use RequestPaths.of(request)");

    @ArchTest static final ArchRule controllersDoNotAccessRepositories = ArchitectureRules.CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES;

    @ArchTest static final ArchRule controllersDoNotExposeEntities = ArchitectureRules.CONTROLLERS_DO_NOT_EXPOSE_ENTITIES;

    @ArchTest static final ArchRule tenantRepositoriesHaveNoUnscopedLookups = ArchitectureRules.TENANT_REPOSITORIES_HAVE_NO_UNSCOPED_LOOKUPS;

    @ArchTest static final ArchRule mutatingControllerMethodsAreAudited = ArchitectureRules.MUTATING_CONTROLLER_METHODS_ARE_AUDITED;

    @ArchTest static final ArchRule communityControllersTakeNoCommunityIdFromTheClient = ArchitectureRules.COMMUNITY_CONTROLLERS_TAKE_NO_COMMUNITY_ID_FROM_THE_CLIENT;

    @ArchTest static final ArchRule noFloatingPointFields = ArchitectureRules.NO_FLOATING_POINT_FIELDS;

    @ArchTest
    static final ArchRule noJavaUtilLogging =
            noClasses().should().dependOnClassesThat().resideInAPackage("java.util.logging..");

    @ArchTest
    static final ArchRule controllersDoNotTouchEntities =
            noClasses()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .dependOnClassesThat()
                    .areAnnotatedWith(Entity.class)
                    .because("API I/O uses DTOs; JPA entities must never leak");

    @ArchTest
    static final ArchRule noFloatingPointFieldsInEntities =
            noFields()
                    .that()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(Entity.class)
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because("money is BigDecimal / NUMERIC(14,2), never floating point");

    @ArchTest
    static final ArchRule toOneAssociationsAreLazy =
            fields()
                    .that()
                    .areAnnotatedWith(ManyToOne.class)
                    .or()
                    .areAnnotatedWith(OneToOne.class)
                    .should(beLazy())
                    .because("eager to-one associations cause N+1 queries and load data nobody asked for");

    private static ArchCondition<JavaField> beLazy() {
        return new ArchCondition<>("use FetchType.LAZY") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                FetchType fetch =
                        field.tryGetAnnotationOfType(ManyToOne.class)
                                .map(ManyToOne::fetch)
                                .orElseGet(() -> field.getAnnotationOfType(OneToOne.class).fetch());
                if (fetch != FetchType.LAZY) {
                    events.add(SimpleConditionEvent.violated(field, field.getFullName() + " is " + fetch));
                }
            }
        };
    }
}
