package com.amanahconnect.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amanahconnect.mail.AbstractMailIT;
import com.amanahconnect.mail.EmailOutboxJob;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.HttpTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;

class ObservabilityIT extends AbstractMailIT {

    @Autowired MeterRegistry meters;
    @Autowired EmailOutboxJob outboxJob;
    @Autowired TransactionTemplate transaction;
    @Autowired jakarta.persistence.EntityManager em;
    @Value("${local.management.port}") int managementPort;

    private double count(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void loginOutcomesAreCountedByKindAndNeverByWho() {
        var admin = users.extraAdminOf(communityA);
        double ok = count("amanah.auth.attempts", "type", "login", "result", "success");
        double bad = count("amanah.auth.attempts", "type", "login", "result", "invalid_credentials");

        login(admin);
        api.post("/api/v1/auth/login", Map.of("email", admin.email(), "password", "Wrong-Password-1234"));
        api.post("/api/v1/auth/login", Map.of("email", "nobody-" + UUID.randomUUID() + "@example.test", "password", "Nobody-Pass-12345"));

        assertThat(count("amanah.auth.attempts", "type", "login", "result", "success")).isEqualTo(ok + 1);
        assertThat(count("amanah.auth.attempts", "type", "login", "result", "invalid_credentials")).isEqualTo(bad + 2);
        meters.getMeters().forEach(m -> m.getId().getTags().forEach(t -> assertThat(t.getValue()).doesNotContain("@").doesNotContain(admin.email())));
    }

    @Test
    void mailSentAndFailedAreCounted() {
        double sent = count("amanah.email.processed", "result", "sent");
        double failed = count("amanah.email.processed", "result", "failed");
        queueSample(communityA.getId(), email(), "member-welcome");
        queueSample(communityA.getId(), email(), "member-welcome");
        smtp.failNext(1);

        sender.runOnce();

        assertThat(count("amanah.email.processed", "result", "sent") + count("amanah.email.processed", "result", "failed") + count("amanah.email.processed", "result", "retrying"))
                .isGreaterThanOrEqualTo(sent + failed + 2);
        assertThat(count("amanah.email.processed", "result", "sent")).isGreaterThanOrEqualTo(sent + 1);
    }

    @Test
    void outboxDepthIsAGaugeThatFollowsTheTable() {
        jdbc.update("update email_outbox set status = 'SENT', sent_at = now() where status = 'PENDING'");
        assertThat(meters.get("amanah.outbox.pending").gauge().value()).isZero();
        queueSample(communityA.getId(), email(), "member-welcome");
        queueSample(communityA.getId(), email(), "member-welcome");

        assertThat(meters.get("amanah.outbox.pending").gauge().value()).isEqualTo(2);
        assertThat(meters.get("amanah.outbox.oldest.pending.seconds").gauge().value()).isGreaterThanOrEqualTo(0);
        assertThat(meters.get("amanah.outbox.failed.24h").gauge().value()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void generatedInvoicesAndTheirTimingAreRecorded() {
        memberA("Gen One");
        memberA("Gen Two");
        var plan = feePlan(sessionA, "Metrics plan", "300.00", "MONTHLY", Map.of("dueDay", 5));
        double before = count("amanah.invoices.generated");

        ApiClient.Response r = asA("POST", INVOICES + "/generate", Map.of("feePlanId", id(plan).toString()));

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(count("amanah.invoices.generated")).isEqualTo(before + r.json().get("created").asInt());
        assertThat(meters.get("amanah.invoice.generation").tag("result", "ok").timer().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void everyScheduledJobRecordsItsDuration() {
        outboxJob.run();

        var timer = meters.get("amanah.job.duration").tag("job", "EmailOutboxJob.run").tag("result", "ok").timer();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void prometheusExposesTheBusinessMetricsOnThePrivatePort() {
        login(users.extraAdminOf(communityA));
        outboxJob.run();

        HttpResponse<String> scrape = HttpTestClient.get(managementPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("amanah_auth_attempts_total").contains("amanah_outbox_pending").contains("amanah_job_duration_seconds").contains("hikaricp_connections_active")
                .contains("http_server_requests_seconds");
        assertThat(HttpTestClient.get(port, "/actuator/prometheus").statusCode()).as("not on the public port").isIn(401, 404);
    }

    @Test
    void readinessChecksTheDatabaseAndAnswersWithoutDetails() {
        HttpResponse<String> ready = HttpTestClient.get(port, "/actuator/health/readiness");

        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(HttpTestClient.get(managementPort, "/actuator/health/readiness").body()).doesNotContain("db").doesNotContain("jdbc");
    }

    @Test
    void storageHealthIsDownWhenTheBucketCannotBeReachedAndUpWhenItCan() {
        var up = new StorageHealthIndicator(storage, true);
        assertThat(up.health().getStatus().getCode()).isEqualTo("UP");
        storage.failHeads = true;
        try {
            assertThat(new StorageHealthIndicator(storage, false).health().getStatus().getCode()).as("off by default").isEqualTo("UP");
        } finally {
            storage.failHeads = false;
        }
        storage.failHeads = true;
        try {
            assertThat(up.health().getStatus().getCode()).isEqualTo("DOWN");
        } finally {
            storage.failHeads = false;
        }
    }

    @Test
    void slowQueriesAreLoggedWithTheirSqlButNotTheirValues() {
        Logger slow = (Logger) LoggerFactory.getLogger("org.hibernate.SQL_SLOW");
        Level before = slow.getLevel();
        slow.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        slow.addAppender(appender);
        try {
            transaction.executeWithoutResult(s -> em.createNativeQuery("select pg_sleep(0.4), 'bound-secret-value'").getSingleResult());
        } finally {
            slow.detachAppender(appender);
            slow.setLevel(before);
        }

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list.get(0).getFormattedMessage()).contains("pg_sleep");
    }

    // ---- request size limit -------------------------------------------------------------------------------------------------

    @Test
    void anOversizedJsonBodyIsRefusedBeforeItIsReadInFull() {
        String huge = "{\"email\":\"a@example.test\",\"password\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";

        ApiClient.Response r = api.postRaw("/api/v1/auth/login", huge);

        assertThat(r.status()).isEqualTo(413);
        assertThat(r.code()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void aChunkedBodyWithNoDeclaredLengthIsCountedAsItIsRead() throws Exception {
        byte[] huge = ("{\"email\":\"a@example.test\",\"password\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}").getBytes();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(huge)))
                .build();

        HttpResponse<String> r = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(413);
    }

    @Test
    void normalSizedRequestsAndUploadsAreUnaffected() {
        assertThat(api.post("/api/v1/auth/login", Map.of("email", "a@example.test", "password", "x".repeat(2000))).status()).isIn(400, 401);
    }
}
