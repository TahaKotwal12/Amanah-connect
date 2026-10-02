package com.amanahconnect.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the production config contract: DB and Flyway connections come from separate env vars,
 * with no defaults, so a missing variable fails startup and Flyway can never silently fall back
 * to the pooled connection.
 */
class ProdProfileConfigTest {

    private final Map<?, ?> prod = load("application-prod.yml");

    @Test
    void applicationDatabaseComesFromEnvironmentWithoutDefaults() {
        assertThat(prod.get("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(prod.get("spring.datasource.username")).isEqualTo("${DB_USER}");
        assertThat(prod.get("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
    }

    @Test
    void flywayUsesItsOwnDirectConnectionVariables() {
        assertThat(prod.get("spring.flyway.url")).isEqualTo("${FLYWAY_URL}");
        assertThat(prod.get("spring.flyway.user")).isEqualTo("${FLYWAY_USER}");
        assertThat(prod.get("spring.flyway.password")).isEqualTo("${FLYWAY_PASSWORD}");
    }

    @Test
    void noLiteralCredentialsOrHostsInProdConfig() {
        prod.forEach(
                (key, value) -> {
                    String k = key.toString().toLowerCase();
                    if (k.contains("password") || k.endsWith(".url") || k.endsWith(".username")) {
                        assertThat(value.toString()).as(k).startsWith("${");
                    }
                });
    }

    @Test
    void sharedConfigMatchesTheDocumentedPoolAndSchemaSettings() {
        Map<?, ?> base = load("application.yml");
        assertThat(base.get("spring.datasource.hikari.maximum-pool-size")).isEqualTo(10);
        assertThat(base.get("spring.datasource.hikari.minimum-idle")).isEqualTo(2);
        assertThat(base.get("spring.datasource.hikari.connection-timeout")).isEqualTo(10000);
        assertThat(base.get("spring.datasource.hikari.max-lifetime")).isEqualTo(1500000);
        assertThat(base.get("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(base.get("spring.jpa.open-in-view")).isEqualTo(false);
        assertThat(base.get("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
        assertThat(base.get("management.server.port")).isEqualTo(8081);
    }

    private static Map<?, ?> load(String file) {
        try {
            PropertySource<?> source =
                    new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).get(0);
            Map<String, Object> plain = new LinkedHashMap<>();
            ((OriginTrackedMapPropertySource) source)
                    .getSource()
                    .forEach(
                            (key, value) -> {
                                Object raw =
                                        value instanceof OriginTrackedValue tracked
                                                ? tracked.getValue()
                                                : value;
                                plain.put(key, raw instanceof CharSequence ? raw.toString() : raw);
                            });
            return plain;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
