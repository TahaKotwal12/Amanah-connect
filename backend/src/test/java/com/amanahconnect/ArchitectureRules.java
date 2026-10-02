package com.amanahconnect;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.audit.Audited;
import com.amanahconnect.tenant.CrossTenantLookup;
import com.amanahconnect.tenant.TenantRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.MappedSuperclass;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The architecture rules, defined once. {@code ArchitectureTest} applies them to the real code;
 * {@code ArchitectureRulesSelfTest} applies them to deliberately bad fixture classes to prove each rule
 * really catches what it claims to (a rule that can never fail protects nothing).
 */
public final class ArchitectureRules {

    private ArchitectureRules() {}

    private static final String COMMUNITY_PREFIX = "/api/v1/community";

    // 1 ---------------------------------------------------------------------------------------

    public static final ArchRule CONTROLLERS_DO_NOT_ACCESS_REPOSITORIES =
            noClasses()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .dependOnClassesThat()
                    .areAssignableTo(Repository.class)
                    .orShould()
                    .dependOnClassesThat()
                    .areAssignableTo(EntityManager.class)
                    .because("controllers go through services, which own transactions, tenant scoping and auditing");

    // 2 ---------------------------------------------------------------------------------------

    public static final ArchRule CONTROLLERS_DO_NOT_EXPOSE_ENTITIES =
            methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(RestController.class)
                    .should(notUseEntitiesInTheirSignature())
                    .because("API input and output are DTOs; a JPA entity in a signature leaks columns, lazy proxies and secrets");

    private static ArchCondition<JavaMethod> notUseEntitiesInTheirSignature() {
        return new ArchCondition<>("neither return nor accept JPA entities (including inside generics)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getReturnType().getAllInvolvedRawTypes().stream()
                        .filter(ArchitectureRules::isEntity)
                        .forEach(type -> events.add(SimpleConditionEvent.violated(method, method.getFullName() + " returns entity " + type.getName())));
                method.getParameterTypes().forEach(parameter -> parameter.getAllInvolvedRawTypes().stream()
                        .filter(ArchitectureRules::isEntity)
                        .forEach(type -> events.add(SimpleConditionEvent.violated(method, method.getFullName() + " accepts entity " + type.getName()))));
            }
        };
    }

    private static boolean isEntity(JavaClass type) {
        return type.isAnnotatedWith(Entity.class) || type.isAnnotatedWith(MappedSuperclass.class);
    }

    // 3 ---------------------------------------------------------------------------------------

    private static final Set<String> READ_PREFIXES = Set.of("find", "get", "read", "query", "stream", "exists", "count", "search");
    private static final Set<String> NEVER_ON_TENANT_REPOSITORIES =
            Set.of("findById", "findAll", "findAllById", "getById", "getReferenceById", "getOne", "existsById");

    public static final ArchRule TENANT_REPOSITORIES_HAVE_NO_UNSCOPED_LOOKUPS =
            classes()
                    .that()
                    .areInterfaces()
                    .and()
                    .areAssignableTo(TenantRepository.class)
                    .should(haveOnlyCommunityScopedLookupsAndNoDeletes())
                    .because("every query on a tenant table takes the community id (or is explicitly marked @CrossTenantLookup)");

    private static ArchCondition<JavaClass> haveOnlyCommunityScopedLookupsAndNoDeletes() {
        return new ArchCondition<>("have only community-scoped lookups and no delete methods") {
            @Override
            public void check(JavaClass repository, ConditionEvents events) {
                for (JavaMethod method : repository.getMethods()) {
                    String name = method.getName();
                    if (NEVER_ON_TENANT_REPOSITORIES.contains(name)) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName() + " looks a row up without a community id"));
                    } else if (name.startsWith("delete") || name.startsWith("remove") || name.startsWith("truncate")) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName() + " deletes rows; tenant data is soft-deleted or reversed"));
                    } else if (isRead(name) && !scoped(method)) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " has no CommunityId in its name or query; add it or mark @CrossTenantLookup with a reason"));
                    }
                }
            }

            private boolean isRead(String name) {
                return READ_PREFIXES.stream().anyMatch(name::startsWith);
            }

            private boolean scoped(JavaMethod method) {
                if (method.getName().contains("CommunityId") || method.isAnnotatedWith(CrossTenantLookup.class)) {
                    return true;
                }
                return method.tryGetAnnotationOfType(Query.class)
                        .map(query -> query.value().contains("communityId") || query.value().contains("community_id"))
                        .orElse(false);
            }
        };
    }

    // 4 ---------------------------------------------------------------------------------------

    public static final ArchRule MUTATING_CONTROLLER_METHODS_ARE_AUDITED =
            methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(RestController.class)
                    .and(areMutatingEndpoints())
                    .should()
                    .beAnnotatedWith(Audited.class)
                    .orShould()
                    .beAnnotatedWith(AuditHandledBy.class)
                    .because("every mutating endpoint writes an audit log: use @Audited, or @AuditHandledBy naming who writes it");

    private static DescribedPredicate<JavaMethod> areMutatingEndpoints() {
        return new DescribedPredicate<>("POST, PUT, PATCH or DELETE endpoints") {
            @Override
            public boolean test(JavaMethod method) {
                if (method.isAnnotatedWith(PostMapping.class) || method.isAnnotatedWith(PutMapping.class)
                        || method.isAnnotatedWith(PatchMapping.class) || method.isAnnotatedWith(DeleteMapping.class)) {
                    return true;
                }
                return method.tryGetAnnotationOfType(RequestMapping.class)
                        .map(mapping -> Arrays.stream(mapping.method()).anyMatch(m -> m != RequestMethod.GET && m != RequestMethod.HEAD && m != RequestMethod.OPTIONS))
                        .orElse(false);
            }
        };
    }

    // 5 ---------------------------------------------------------------------------------------

    public static final ArchRule COMMUNITY_CONTROLLERS_TAKE_NO_COMMUNITY_ID_FROM_THE_CLIENT =
            classes()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .and(mappedUnderCommunity())
                    .should(acceptNoCommunityIdFromTheRequest())
                    .allowEmptyShould(true)
                    .because("the tenant comes from the authenticated principal (@CurrentCommunity), never from request input");

    private static DescribedPredicate<JavaClass> mappedUnderCommunity() {
        return new DescribedPredicate<>("mapped under " + COMMUNITY_PREFIX) {
            @Override
            public boolean test(JavaClass type) {
                return type.tryGetAnnotationOfType(RequestMapping.class)
                        .map(mapping -> Arrays.stream(mapping.value()).anyMatch(path -> path.startsWith(COMMUNITY_PREFIX)))
                        .orElse(false);
            }
        };
    }

    private static ArchCondition<JavaClass> acceptNoCommunityIdFromTheRequest() {
        return new ArchCondition<>("not read a community id from the path, query or body") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                for (JavaMethod method : controller.getMethods()) {
                    for (String template : mappingPaths(method)) {
                        if (template.toLowerCase().contains("communityid")) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName() + " maps a community id in its path " + template));
                        }
                    }
                    for (JavaParameter parameter : method.getParameters()) {
                        parameter.tryGetAnnotationOfType(PathVariable.class).ifPresent(a -> flag(events, method, a.value() + a.name()));
                        parameter.tryGetAnnotationOfType(RequestParam.class).ifPresent(a -> flag(events, method, a.value() + a.name()));
                        if (parameter.isAnnotatedWith(RequestBody.class)) {
                            parameter.getRawType().getAllFields().stream()
                                    .filter(field -> field.getName().equalsIgnoreCase("communityId"))
                                    .forEach(field -> events.add(SimpleConditionEvent.violated(method,
                                            method.getFullName() + " accepts a body (" + parameter.getRawType().getSimpleName() + ") that carries communityId")));
                        }
                    }
                }
            }

            private void flag(ConditionEvents events, JavaMethod method, String names) {
                if (names.toLowerCase().contains("communityid")) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName() + " takes a community id from the request"));
                }
            }

            private List<String> mappingPaths(JavaMethod method) {
                return Arrays.asList(
                        method.tryGetAnnotationOfType(GetMapping.class).map(a -> String.join(",", a.value())).orElse(""),
                        method.tryGetAnnotationOfType(PostMapping.class).map(a -> String.join(",", a.value())).orElse(""),
                        method.tryGetAnnotationOfType(PutMapping.class).map(a -> String.join(",", a.value())).orElse(""),
                        method.tryGetAnnotationOfType(PatchMapping.class).map(a -> String.join(",", a.value())).orElse(""),
                        method.tryGetAnnotationOfType(DeleteMapping.class).map(a -> String.join(",", a.value())).orElse(""));
            }
        };
    }

    // 6 ---------------------------------------------------------------------------------------

    public static final ArchRule NO_FLOATING_POINT_FIELDS =
            noFields()
                    .should()
                    .haveRawType(double.class)
                    .orShould()
                    .haveRawType(float.class)
                    .orShould()
                    .haveRawType(Double.class)
                    .orShould()
                    .haveRawType(Float.class)
                    .because("money is BigDecimal (NUMERIC(14,2) in the database, a string in JSON); floating point loses cents");
}
