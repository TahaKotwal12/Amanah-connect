package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ReportIT extends AbstractFinanceIT {

    private static final List<String> KINDS = List.of("income-expense", "category-breakdown", "collection", "defaulters", "receipts-register");

    private void growth() {
        jdbc.update("update communities set plan_id = (select id from plans where code = 'GROWTH') where id = ?", communityA.getId());
    }

    private ApiClient.BinaryResponse get(String path) {
        return bytesA(REPORTS + path);
    }

    private String csv(String path) {
        ApiClient.BinaryResponse response = get(path);
        assertThat(response.status()).isEqualTo(200);
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private UUID paidAndOverdue() {
        UUID m = memberA("Report Member");
        payOk(sessionA, id(invoiceA(m, "1000.00", TODAY.plusDays(5))), "400.00");
        invoiceA(m, "250.00", TODAY.minusDays(3));
        jdbc.update("update invoices set status = 'OVERDUE' where community_id = ? and due_date < current_date", communityA.getId());
        return m;
    }

    @Test
    void everyReportDownloadsAsCsvAndPdf() {
        growth();
        UUID m = paidAndOverdue();
        for (String kind : KINDS) {
            ApiClient.BinaryResponse csv = get("/" + kind + "?format=csv");
            assertThat(csv.status()).as(kind).isEqualTo(200);
            assertThat(csv.body().length).isPositive();
            ApiClient.BinaryResponse pdf = get("/" + kind + "?format=pdf");
            assertThat(pdf.status()).as(kind).isEqualTo(200);
            assertThat(new String(pdf.body(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        }
        assertThat(get("/member-dues?format=csv&memberId=" + m).status()).isEqualTo(200);
        assertThat(pdfText(get("/member-dues?format=pdf&memberId=" + m).body())).contains("Report Member");
    }

    @Test
    void collectionAndDefaultersReflectTheData() {
        paidAndOverdue();
        assertThat(csv("/collection")).contains("1250.00").contains("400.00").contains("850.00");
        assertThat(csv("/defaulters")).contains("Report Member").contains("250.00");
        assertThat(csv("/receipts-register")).contains("RCP-");
    }

    @Test
    void memberDuesNeedsAMember() {
        assertThat(get("/member-dues").status()).isEqualTo(400);
    }

    @Test
    void pdfNeedsThePlanFeature() {
        ApiClient.BinaryResponse response = get("/income-expense?format=pdf");
        assertThat(response.status()).isEqualTo(402);
    }

    @Test
    void csvNeedsItsPlanFeature() {
        jdbc.update("update plans set features = features || '{\"csv_export\": false}'::jsonb where id = (select plan_id from communities where id = ?)", communityA.getId());
        try {
            assertThat(get("/income-expense").status()).isEqualTo(402);
        } finally {
            jdbc.update("update plans set features = features || '{\"csv_export\": true}'::jsonb where id = (select plan_id from communities where id = ?)", communityA.getId());
        }
    }

    @Test
    void unknownReportAndBadFormatAreRejected() {
        assertThat(get("/nope").status()).isEqualTo(404);
        assertThat(get("/collection?format=xlsx").status()).isEqualTo(400);
        assertThat(get("/collection?from=2026-05-02&to=2026-05-01").status()).isEqualTo(400);
    }

    @Test
    void textCellsAreGuardedAgainstFormulaInjectionButNegativeNumbersAreNot() {
        UUID m = member(sessionA, "=HYPERLINK(\"http://evil\")", email(), true);
        invoiceA(m, "100.00", TODAY.minusDays(2));
        jdbc.update("update invoices set status = 'OVERDUE' where community_id = ? and due_date < current_date", communityA.getId());
        assertThat(csv("/defaulters")).contains("'=HYPERLINK").doesNotContain(",=HYPERLINK");

        Map<String, Object> entry = new LinkedHashMap<>();
        for (JsonNode c : asA("GET", LEDGER + "/categories", null).json()) {
            if (c.get("name").asString().equals("Maintenance") && c.get("type").asString().equals("EXPENSE")) entry.put("categoryId", c.get("id").asString());
        }
        entry.put("type", "EXPENSE");
        entry.put("amount", "10.00");
        entry.put("entryDate", TODAY.toString());
        entry.put("title", "x");
        JsonNode created = asA("POST", LEDGER + "/entries", entry).json();
        assertThat(asA("POST", LEDGER + "/entries/" + created.get("id").asString() + "/reverse", Map.of("reason", "oops")).status()).isEqualTo(201);
        assertThat(csv("/income-expense")).doesNotContain("'-");
    }

    @Test
    void exportsAreAudited() {
        long before = count("select count(*) from audit_logs where community_id = ? and action = 'REPORT_EXPORTED'", communityA.getId());
        assertThat(get("/collection").status()).isEqualTo(200);
        assertThat(count("select count(*) from audit_logs where community_id = ? and action = 'REPORT_EXPORTED'", communityA.getId())).isEqualTo(before + 1);
    }

    @Test
    void reportsNeverIncludeAnotherCommunitysData() {
        UUID b = member(sessionB, "Other Tenant Person", email(), true);
        invoice(sessionB, b, "777.00", TODAY.minusDays(9));
        jdbc.update("update invoices set status = 'OVERDUE' where community_id = ? and due_date < current_date", communityB.getId());
        for (String kind : KINDS) {
            assertThat(csv("/" + kind)).as(kind).doesNotContain("Other Tenant Person").doesNotContain("777.00");
        }
        assertThat(get("/member-dues?memberId=" + b).status()).isEqualTo(404);
    }
}
