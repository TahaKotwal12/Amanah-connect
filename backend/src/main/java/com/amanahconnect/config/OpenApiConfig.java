package com.amanahconnect.config;

import io.swagger.v3.oas.models.Components;
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

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("Admin (SUPER_ADMIN)")
                .pathsToMatch("/api/v1/admin/**")
                .addOpenApiCustomizer(openApi -> openApi.addSecurityItem(new SecurityRequirement().addList(BEARER)))
                .build();
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder().group("public").displayName("Public").pathsToMatch("/api/v1/public/**", "/api/v1/ping").build();
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder().group("auth").displayName("Authentication").pathsToMatch("/api/v1/auth/**").build();
    }
}
