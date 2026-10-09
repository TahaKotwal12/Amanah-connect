package com.amanahconnect.dataexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.mail.AbstractMailIT;
import com.amanahconnect.mail.OutgoingMail;
import com.amanahconnect.support.ApiClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class DataExportIT extends AbstractMailIT {

    private static final String X = "/api/v1/community/data-exports";
    private static final Pattern LINK = Pattern.compile("/api/v1/public/exports/([A-Za-z0-9_-]{43})");

    @Autowired DataExportService exports;
    @Autowired ExportCatalog catalog;

    private UUID requestOk(Session s) {
        ApiClient.Response r = call(s, "POST", X, null);
        assertThat(r.status()).as(r.body()).isEqualTo(202);
        assertThat(r.json().get("status").asString()).isEqualTo("PENDING");
        return id(r.json());
    }

    private Map<String, String> unzip(UUID exportId) throws Exception {
        String key = jdbc.queryForObject("select object_key from data_exports where id = ?", String.class, exportId);
        byte[] zip = storage.get(key).orElseThrow();
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private String statusOf(UUID exportId) {
        return jdbc.queryForObject("select status from data_exports where id = ?", String.class, exportId);
    }

    private void seed() {
        UUID m = member(sessionA, "Export Person", "export.person@example.test", true);
        UUID inv = id(invoiceA(m, "1234.50", TODAY.plusDays(5)));
        payOk(sessionA, inv, "200.25");
        asA("POST", "/api/v1/community/complaints", Map.of("subject", "Leaking tap", "description", "kitchen"));
        UUID mb = member(sessionB, "Other Community Person", "other.person@example.test", true);
        payOk(sessionB, id(invoice(sessionB, mb, "999.00", TODAY.plusDays(5))), "10.00");
    }

    @Test
    void askingAnswers202AtOnceAndOnlyOneAtATimeIsAllowed() {
        UUID first = requestOk(sessionA);

        ApiClient.Response second = asA("POST", X, null);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("EXPORT_IN_PROGRESS");

        assertThat(asA("GET", X, null).json()).hasSize(1);
        assertThat(asA("GET", X + "/" + first, null).json().get("status").asString()).isEqualTo("PENDING");
        assertThat(count("select count(*) from audit_logs where action = 'DATA_EXPORT_REQUESTED' and entity_id = ?", first)).isEqualTo(1);
    }

    @Test
    void theZipHasOneCsvPerTableWithTheCommunitysDataAndNobodyElses() throws Exception {
        seed();
        UUID export = requestOk(sessionA);

        DataExportService.WorkReport report = exports.work();

        assertThat(report.failed()).isZero();
        assertThat(statusOf(export)).isEqualTo("READY");
        Map<String, String> files = unzip(export);

        assertThat(files.keySet()).contains("members.csv", "invoices.csv", "payment_records.csv", "complaints.csv", "communities.csv", "community_admins.csv", "audit_logs.csv");
        assertThat(files.get("members.csv")).contains("Export Person").contains("export.person@example.test");
        assertThat(files.get("invoices.csv")).contains("1234.50");
        assertThat(files.get("payment_records.csv")).contains("200.25");
        assertThat(files.get("complaints.csv")).contains("Leaking tap");
        assertThat(files.get("audit_logs.csv")).contains("DATA_EXPORT_REQUESTED");
        assertThat(files.get("community_admins.csv")).contains(adminA.email()).doesNotContain(adminB.email());
        files.forEach((name, body) -> {
            assertThat(body).as(name).doesNotContain("Other Community Person").doesNotContain("other.person@example.test").doesNotContain("999.00");
            assertThat(body).as(name).doesNotContain(communityB.getId().toString());
        });
    }

    @Test
    void everyTenantTableIsInTheZipAndSecretsAreNot() throws Exception {
        seed();
        UUID export = requestOk(sessionA);
        exports.work();

        Map<String, String> files = unzip(export);

        for (String table : catalog.exportedTenantTables()) {
            assertThat(files).as("table " + table + " must be exported").containsKey(table + ".csv");
        }
        String all = String.join("\n", files.values());
        files.forEach((name, body) -> {
            String header = body.replace("﻿", "").lines().findFirst().orElse("");
            assertThat(header).as(name).doesNotContain("token_hash").doesNotContain("password_hash").doesNotContain("request_hash");
        });
        assertThat(all).doesNotContain("$2a$").doesNotContain("$argon2");
        assertThat(files).as("the list of exports is part of the data, the secrets in it are not").containsKey("data_exports.csv");
    }

    @Test
    void rowCountsInTheSummaryMatchTheFiles() throws Exception {
        seed();
        UUID export = requestOk(sessionA);
        exports.work();

        JsonNode view = asA("GET", X + "/" + export, null).json();
        Map<String, String> files = unzip(export);

        assertThat(view.get("status").asString()).isEqualTo("READY");
        assertThat(view.get("sizeBytes").asLong()).isPositive();
        assertThat(view.get("linkExpiresAt").isNull()).isFalse();
        JsonNode tables = view.get("tables");
        long checked = 0;
        for (Map.Entry<String, String> f : files.entrySet()) {
            String table = f.getKey().replace(".csv", "");
            if (!tables.has(table)) continue;
            long lines = f.getValue().replace("﻿", "").strip().lines().count();
            assertThat(tables.get(table).asLong()).as(table).isLessThanOrEqualTo(Math.max(lines, 1)); // quoted newlines may add lines, never remove
            checked++;
        }
        assertThat(checked).isGreaterThan(5);
        assertThat(tables.get("members").asLong()).isEqualTo(1);
        assertThat(tables.get("invoices").asLong()).isEqualTo(1);
        assertThat(tables.get("payment_records").asLong()).isEqualTo(1);
    }

    @Test
    void theRequesterGetsAnEmailWithATimeLimitedLinkThatWorksWithoutSigningIn() {
        seed();
        UUID export = requestOk(sessionA);
        exports.work();
        sender.runOnce();

        List<OutgoingMail> mails = smtp.sentTo(adminA.email());
        OutgoingMail mail = mails.stream().filter(m -> m.text().contains("/api/v1/public/exports/")).findFirst().orElseThrow();
        Matcher found = LINK.matcher(mail.text());
        assertThat(found.find()).isTrue();
        String token = found.group(1);
        assertThat(mail.html()).contains(token);

        ApiClient.Response opened = api.get("/api/v1/public/exports/" + token);
        assertThat(opened.status()).isEqualTo(302);
        assertThat(opened.header("Location")).startsWith("https://storage.example.test/download/communities/" + communityA.getId() + "/exports/");
        assertThat(opened.header("Cache-Control")).contains("no-store");
        assertThat(asA("GET", X + "/" + export, null).json().get("downloadCount").asInt()).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'DATA_EXPORT_DOWNLOADED' and entity_id = ?", export)).isEqualTo(1);

        assertThat(jdbc.queryForObject("select token_hash from data_exports where id = ?", String.class, export)).isNotEqualTo(token).hasSize(64);
        assertThat(jdbc.queryForObject("select count(*) from data_exports where token_hash = ?", Long.class, token)).isZero();
        Object payload = jdbc.queryForObject("select payload::text from email_outbox where to_email = ? and template = 'data-export-ready'", String.class, adminA.email());
        assertThat(payload.toString()).as("the link is not kept once the mail is sent").doesNotContain(token);
    }

    @Test
    void unknownMalformedAndExpiredLinksAllLookTheSame() {
        seed();
        UUID export = requestOk(sessionA);
        exports.work();
        sender.runOnce();
        String token = tokenFromMail();

        ApiClient.Response unknown = api.get("/api/v1/public/exports/" + "a".repeat(43));
        ApiClient.Response malformed = api.get("/api/v1/public/exports/short");
        jdbc.update("update data_exports set link_expires_at = now() - interval '1 minute' where id = ?", export);
        ApiClient.Response expired = api.get("/api/v1/public/exports/" + token);

        for (ApiClient.Response r : List.of(unknown, malformed, expired)) {
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.code()).isEqualTo("EXPORT_LINK_UNAVAILABLE");
        }
        assertThat(unknown.json().get("detail")).isEqualTo(malformed.json().get("detail"));
        assertThat(unknown.json().get("detail")).isEqualTo(expired.json().get("detail"));
    }

    private String tokenFromMail() {
        OutgoingMail mail = smtp.sentTo(adminA.email()).stream().filter(m -> m.text().contains("/api/v1/public/exports/")).findFirst().orElseThrow();
        Matcher m = LINK.matcher(mail.text());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void whenTheLinkRunsOutTheFileIsDeletedAndTheExportExpires() {
        seed();
        UUID export = requestOk(sessionA);
        exports.work();
        String key = jdbc.queryForObject("select object_key from data_exports where id = ?", String.class, export);
        assertThat(storage.has(key)).isTrue();

        jdbc.update("update data_exports set link_expires_at = now() - interval '1 minute' where id = ?", export);
        DataExportService.WorkReport report = exports.work();

        assertThat(report.expired()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(export)).isEqualTo("EXPIRED");
        assertThat(storage.has(key)).isFalse();
        assertThat(jdbc.queryForObject("select token_hash from data_exports where id = ?", String.class, export)).isNull();
        assertThat(asA("GET", X + "/" + export + "/download-url", null).status()).isEqualTo(404);
        assertThat(asA("GET", X + "/" + export, null).json().get("status").asString()).isEqualTo("EXPIRED");
        assertThat(count("select count(*) from audit_logs where action = 'DATA_EXPORT_EXPIRED' and entity_id = ?", export)).isEqualTo(1);
    }

    @Test
    void aSignedInAdminCanGetAFreshShortLivedAddressAndItIsAudited() {
        seed();
        UUID export = requestOk(sessionA);
        assertThat(asA("GET", X + "/" + export + "/download-url", null).status()).as("not ready yet").isEqualTo(404);
        exports.work();

        ApiClient.Response r = asA("GET", X + "/" + export + "/download-url", null);

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().get("url").asString()).startsWith("https://storage.example.test/download/");
        assertThat(r.json().get("validForSeconds").asLong()).isEqualTo(300);
        assertThat(count("select count(*) from audit_logs where action = 'DATA_EXPORT_DOWNLOADED' and entity_id = ?", export)).isEqualTo(1);
    }

    @Test
    void aFailureIsRecordedAndDoesNotBlockTheNextAttempt() {
        seed();
        UUID export = requestOk(sessionA);
        storage.failPuts = true;
        DataExportService.WorkReport report;
        try {
            report = exports.work();
        } finally {
            storage.failPuts = false;
        }

        assertThat(report.failed()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(export)).isEqualTo("FAILED");
        JsonNode view = asA("GET", X + "/" + export, null).json();
        assertThat(view.get("error").asString()).isNotBlank();
        assertThat(count("select count(*) from audit_logs where action = 'DATA_EXPORT_FAILED' and entity_id = ?", export)).isEqualTo(1);
        assertThat(smtp.sentTo(adminA.email()).stream().filter(m -> m.text().contains("/api/v1/public/exports/"))).isEmpty();

        UUID retry = requestOk(sessionA);
        exports.work();
        assertThat(statusOf(retry)).isEqualTo("READY");
    }

    @Test
    void aRunThatCrashedIsPickedUpAgain() {
        seed();
        UUID export = requestOk(sessionA);
        jdbc.update("update data_exports set status = 'RUNNING', started_at = now() - interval '2 hours' where id = ?", export);

        exports.work();

        assertThat(statusOf(export)).isEqualTo("READY");
    }

    @Test
    void atMostThreeExportsInADay() {
        for (int i = 0; i < 3; i++) {
            requestOk(sessionA);
            exports.work();
        }

        ApiClient.Response fourth = asA("POST", X, null);

        assertThat(fourth.status()).isEqualTo(429);
        assertThat(fourth.header("Retry-After")).isNotBlank();
        assertThat(requestOk(sessionB)).as("another community is unaffected").isNotNull();
    }

    @Test
    void exportsAreInvisibleToOtherCommunities() {
        UUID export = requestOk(sessionA);
        exports.work();

        assertThat(asB("GET", X + "/" + export, null).status()).isEqualTo(404);
        assertThat(asB("GET", X + "/" + export + "/download-url", null).status()).isEqualTo(404);
        assertThat(asB("GET", X, null).json()).isEmpty();
        assertThat(api.call("POST", X, null).status()).isEqualTo(401);
        assertThat(asSuper("POST", X, null).status()).as("platform staff have no community").isIn(400, 401, 403, 404);
    }

    @Test
    void theExportNeverIncludesItsOwnLinkOrAnotherExportsToken() throws Exception {
        UUID first = requestOk(sessionA);
        exports.work();
        sender.runOnce();
        String token = tokenFromMail();
        UUID second = requestOk(sessionA);
        exports.work();

        Map<String, String> files = unzip(second);

        assertThat(String.join("\n", files.values())).doesNotContain(token);
        assertThat(files.get("data_exports.csv")).contains(first.toString());
    }
}
