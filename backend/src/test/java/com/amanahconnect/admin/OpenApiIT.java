package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.support.AdminEndpoints;
import com.amanahconnect.support.AdminEndpoints.Endpoint;
import com.amanahconnect.support.ApiClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/** The OpenAPI document shows every /api/v1/admin endpoint, grouped under its own tag. Docs are off by default; this context turns them on. */
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class OpenApiIT extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    private JsonNode doc(String group) {
        ApiClient.Response response = new ApiClient(port).get("/v3/api-docs/" + group);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    void theAdminGroupListsEveryAdminEndpointAndNothingElse() {
        JsonNode admin = doc("admin");
        Set<String> documented = new LinkedHashSet<>();
        admin.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
            if (List.of("get", "post", "put", "patch", "delete").contains(op.getKey())) {
                documented.add(op.getKey().toUpperCase() + " " + path.getKey());
            }
        }));
        List<String> expected = AdminEndpoints.all(handlerMapping).stream().map(Endpoint::toString).toList();

        assertThat(expected).hasSizeGreaterThanOrEqualTo(30);
        assertThat(documented).as("documented vs mapped admin endpoints").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(admin.get("paths").properties().stream().map(e -> e.getKey())).allMatch(p -> p.startsWith("/api/v1/admin/"));
    }

    @Test
    void everyAdminOperationIsTaggedWithItsAreaAndRequiresABearerToken() {
        JsonNode admin = doc("admin");
        Set<String> tags = new LinkedHashSet<>();
        List<String> untagged = new ArrayList<>();
        admin.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
            JsonNode node = op.getValue();
            if (!node.has("tags") || node.get("tags").size() != 1 || !node.get("tags").get(0).asString().startsWith("Admin · ")) {
                untagged.add(op.getKey() + " " + path.getKey());
            } else {
                tags.add(node.get("tags").get(0).asString());
            }
            assertThat(node.has("summary")).as(op.getKey() + " " + path.getKey() + " has a summary").isTrue();
        }));

        assertThat(untagged).isEmpty();
        assertThat(tags).containsExactlyInAnyOrder(
                "Admin · Communities", "Admin · Plans", "Admin · Subscriptions", "Admin · Leads", "Admin · Import", "Admin · Stats", "Admin · Support desk", "Admin · Announcements");
        assertThat(admin.get("security").get(0).has("bearerAuth")).isTrue();
        assertThat(admin.get("components").get("securitySchemes").get("bearerAuth").get("scheme").asString()).isEqualTo("bearer");
    }

    @Test
    void publicAndAuthEndpointsAreInTheirOwnGroups() {
        JsonNode publicDoc = doc("public");
        assertThat(publicDoc.get("paths").has("/api/v1/public/leads")).isTrue();
        assertThat(publicDoc.get("paths").properties().stream().map(e -> e.getKey())).noneMatch(p -> p.startsWith("/api/v1/admin"));

        JsonNode auth = doc("auth");
        assertThat(auth.get("paths").has("/api/v1/auth/login")).isTrue();
        assertThat(auth.get("paths").properties().stream().map(e -> e.getKey())).allMatch(p -> p.startsWith("/api/v1/auth/"));
    }

    @Test
    void theGroupsAreAdvertisedToSwaggerUi() {
        ApiClient.Response response = new ApiClient(port).get("/v3/api-docs/swagger-config");
        assertThat(response.status()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        response.json().get("urls").forEach(u -> names.add(u.get("name").asString()));
        assertThat(names).contains("Admin (SUPER_ADMIN)", "Public", "Authentication");
    }
}
