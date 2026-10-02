package com.amanahconnect;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.support.HttpTestClient;
import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpResponse;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BackendSkeletonIT extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Value("${local.management.port}") int managementPort;

    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Environment env;

    // --- ping --------------------------------------------------------------------------------

    @Test
    void pingIsPublicAndReturnsVersionAndTime() throws Exception {
        HttpResponse<String> res = HttpTestClient.get(port, "/api/v1/ping");

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.get("version").asString()).isNotBlank().isNotEqualTo("unknown");
        assertThat(java.time.Instant.parse(body.get("time").asString())).isNotNull();
    }

    // --- health ------------------------------------------------------------------------------

    @Test
    void readinessAndLivenessAreUpOnTheMainPortWithoutDetails() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            HttpResponse<String> res = HttpTestClient.get(port, path);
            assertThat(res.statusCode()).as(path).isEqualTo(200);
            assertThat(json.readTree(res.body()).propertyNames()).as(path).containsExactly("status");
            assertThat(json.readTree(res.body()).get("status").asString()).as(path).isEqualTo("UP");
        }
    }

    @Test
    void mainPortDoesNotExposeInfoOrPrometheus() throws Exception {
        for (String path : List.of("/actuator/prometheus", "/actuator/info", "/actuator/env")) {
            HttpResponse<String> res = HttpTestClient.get(port, path);
            assertThat(res.statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void managementPortServesHealthInfoAndPrometheus() {
        assertThat(managementPort).isNotEqualTo(port);
        assertThat(HttpTestClient.get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(HttpTestClient.get(managementPort, "/actuator/info").statusCode()).isEqualTo(200);
        HttpResponse<String> prometheus = HttpTestClient.get(managementPort, "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void managementPortDoesNotExposeOtherActuatorEndpoints() {
        assertThat(HttpTestClient.get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(HttpTestClient.get(managementPort, "/actuator/beans").statusCode()).isEqualTo(404);
    }

    // --- security and errors -----------------------------------------------------------------

    @Test
    void unauthenticatedRequestGetsProblemJsonWithStableCode() throws Exception {
        HttpResponse<String> res = HttpTestClient.get(port, "/api/v1/members");

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode body = json.readTree(res.body());
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("type").asString()).isEqualTo("urn:amanah:problem:unauthenticated");
    }

    @Test
    void requestIdIsEchoedAndAppearsInProblemBody() throws Exception {
        HttpResponse<String> res = HttpTestClient.get(port, "/api/v1/members", "X-Request-Id", "req-42");

        assertThat(res.headers().firstValue("X-Request-Id")).contains("req-42");
        assertThat(json.readTree(res.body()).get("requestId").asString()).isEqualTo("req-42");
    }

    @Test
    void securityHeadersArePresent() {
        HttpResponse<String> res = HttpTestClient.get(port, "/api/v1/ping");

        assertThat(res.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(res.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(res.headers().firstValue("Content-Security-Policy").orElse("")).contains("frame-ancestors 'none'");
        assertThat(res.headers().firstValue("Referrer-Policy")).contains("no-referrer");
    }

    @Test
    void corsAllowsConfiguredOriginOnly() {
        assertThat(
                        HttpTestClient.get(port, "/api/v1/ping", "Origin", "http://localhost:5173")
                                .headers()
                                .firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
        assertThat(
                        HttpTestClient.get(port, "/api/v1/ping", "Origin", "https://evil.example")
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void apiDocsAreNotPublicOutsideLocalProfile() {
        assertThat(HttpTestClient.get(port, "/v3/api-docs").statusCode()).isEqualTo(401);
        assertThat(HttpTestClient.get(port, "/swagger-ui/index.html").statusCode()).isEqualTo(401);
    }

    // --- database ----------------------------------------------------------------------------

    @Test
    void flywayBaselineEnabledTheRequiredExtensions() {
        List<String> extensions = jdbc.queryForList("select extname from pg_extension", String.class);
        assertThat(extensions).contains("citext", "pgcrypto");

        assertThat(
                        jdbc.queryForObject(
                                "select success from flyway_schema_history where version = '1'",
                                Boolean.class))
                .isTrue();
    }

    @Test
    void hikariPoolUsesTheDocumentedSettings() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
        assertThat(hikari.getMinimumIdle()).isEqualTo(2);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(10_000);
        assertThat(hikari.getMaxLifetime()).isEqualTo(25 * 60 * 1000);
    }

    @Test
    void hibernateValidatesAndRunsInUtc() {
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(env.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
    }
}
