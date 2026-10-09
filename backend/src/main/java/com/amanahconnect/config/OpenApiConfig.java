package com.amanahconnect.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API description. Endpoints are split into groups (pick one in Swagger UI, or fetch
 * {@code /v3/api-docs/admin} etc.): the platform-admin API, the public API and sign-in. Inside a group, controllers
 * carry an {@code @Tag}, so the admin group reads as Communities, Plans, Subscriptions, Leads, Import and Stats.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI amanahOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Amanah Connect API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login (and /login/2fa). /api/v1/admin needs a SUPER_ADMIN whose 2FA is set up.")));
    }

    /** Every error is RFC 9457 problem+json with a stable {@code code}. */
    static final String PROBLEM = "Problem";

    private static Schema<?> problemSchema() {
        Schema<?> schema = new Schema<>().type("object").description("RFC 9457 problem details. Branch on code, never on title or detail.");
        schema.addProperty("type", new Schema<String>().type("string").example("urn:amanah:problem:not-found"));
        schema.addProperty("title", new Schema<String>().type("string").example("Not found"));
        schema.addProperty("status", new Schema<Integer>().type("integer").example(404));
        schema.addProperty("detail", new Schema<String>().type("string").example("The resource was not found."));
        schema.addProperty("code", new Schema<String>().type("string").example("NOT_FOUND").description("Stable machine-readable code."));
        schema.addProperty("requestId", new Schema<String>().type("string").example("9df390e4-19ec-4fdb-8545-ddac1f6669f2").description("Quote this when reporting a problem."));
        schema.addProperty("errors", new Schema<>().type("array").description("Field errors for VALIDATION_FAILED.")
                .items(new Schema<>().type("object").addProperty("field", new Schema<String>().type("string").example("amount"))
                        .addProperty("message", new Schema<String>().type("string").example("must be greater than 0"))));
        return schema;
    }

    private static ApiResponse problem(String description, String code, int status) {
        MediaType media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))
                .example(Map.of("type", "urn:amanah:problem:" + code.toLowerCase().replace('_', '-'), "title", description, "status", status, "code", code, "requestId", "9df390e4-19ec-4fdb-8545-ddac1f6669f2"));
        return new ApiResponse().description(description).content(new Content().addMediaType("application/problem+json", media));
    }

    /** Adds the standard error answers to every operation that does not describe its own. */
    @Bean
    public OpenApiCustomizer problemResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) openApi.setComponents(new Components());
            openApi.getComponents().addSchemas(PROBLEM, problemSchema());
            if (openApi.getPaths() == null) return;
            for (Map.Entry<String, PathItem> path : openApi.getPaths().entrySet()) {
                boolean secured = path.getKey().startsWith("/api/v1/admin/") || path.getKey().startsWith("/api/v1/community/");
                for (Operation op : path.getValue().readOperations()) {
                    var responses = op.getResponses();
                    if (secured) {
                        responses.putIfAbsent("401", problem("Authentication is required", "UNAUTHENTICATED", 401));
                        responses.putIfAbsent("403", problem("Not allowed", "FORBIDDEN", 403));
                    }
                    if (path.getKey().contains("{")) responses.putIfAbsent("404", problem("Not found (also returned for another community's data)", "NOT_FOUND", 404));
                    if (op.getRequestBody() != null) responses.putIfAbsent("400", problem("Invalid input", "VALIDATION_FAILED", 400));
                    responses.putIfAbsent("429", problem("Too many requests", "RATE_LIMITED", 429));
                    responses.putIfAbsent("500", problem("Unexpected error; quote the requestId", "INTERNAL_ERROR", 500));
                }
            }
        };
    }

    @Bean
    public GroupedOpenApi communityApi() {
        return GroupedOpenApi.builder()
                .group("community")
                .displayName("Community (COMMUNITY_ADMIN)")
                .pathsToMatch("/api/v1/community/**")
                .addOpenApiCustomizer(problemResponses())
                .addOpenApiCustomizer(openApi -> openApi.addSecurityItem(new SecurityRequirement().addList(BEARER)))
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("Admin (SUPER_ADMIN)")
                .pathsToMatch("/api/v1/admin/**")
                .addOpenApiCustomizer(problemResponses())
                .addOpenApiCustomizer(openApi -> openApi.addSecurityItem(new SecurityRequirement().addList(BEARER)))
                .build();
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder().group("public").displayName("Public").pathsToMatch("/api/v1/public/**", "/api/v1/ping").addOpenApiCustomizer(problemResponses()).build();
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder().group("auth").displayName("Authentication").pathsToMatch("/api/v1/auth/**").addOpenApiCustomizer(problemResponses()).build();
    }
}
