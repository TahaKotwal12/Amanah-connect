package com.amanahconnect;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
    static final ArchRule noJavaUtilLogging =
            noClasses().should().dependOnClassesThat().resideInAPackage("java.util.logging..");
}
