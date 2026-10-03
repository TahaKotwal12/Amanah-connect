package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.InMemoryObjectStorage;
import com.amanahconnect.support.TestData;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Shared helpers for the finance tests: members, fee plans, invoices and payments, all through the real API. */
public abstract class AbstractFinanceIT extends AbstractTenantIT {

    protected static final String PLANS = "/api/v1/community/fee-plans";
    protected static final String INVOICES = "/api/v1/community/invoices";
    protected static final String PAYMENTS = "/api/v1/community/payments";
    protected static final String RECEIPTS = "/api/v1/community/receipts";
    protected static final String DONATIONS = "/api/v1/community/donations";
    protected static final String LEDGER = "/api/v1/community/ledger";
    protected static final String REPORTS = "/api/v1/community/reports";
    protected static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @Autowired protected InMemoryObjectStorage storage;

    protected String email() {
        return "f-" + TestData.unique() + "@example.test";
    }

    protected UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    protected UUID member(Session session, String name, String email, boolean consent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", name);
        if (email != null) body.put("email", email);
        body.put("consentEmail", consent);
        body.put("allowDuplicateEmail", true);
        ApiClient.Response response = call(session, "POST", "/api/v1/community/members", body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return id(response.json());
    }

    protected UUID memberA(String name) {
        return member(sessionA, name, email(), true);
    }

    protected JsonNode feePlan(Session session, String name, String amount, String frequency, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("kind", "MAINTENANCE");
        body.put("amount", amount);
        body.put("frequency", frequency);
        body.putAll(extra);
        ApiClient.Response response = call(session, "POST", PLANS, body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    protected JsonNode generate(Session session, UUID planId, String period) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("feePlanId", planId.toString());
        if (period != null) body.put("period", period);
        ApiClient.Response response = call(session, "POST", INVOICES + "/generate", body);
        assertThat(response.status()).as(response.body()).isBetween(200, 201);
        return response.json();
    }

    /** A one-off issued invoice for a member in community A. */
    protected JsonNode invoiceA(UUID memberId, String amount, LocalDate due) {
        return invoice(sessionA, memberId, amount, due);
    }

    protected JsonNode invoice(Session session, UUID memberId, String amount, LocalDate due) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("memberId", memberId.toString());
        body.put("kind", "MAINTENANCE");
        body.put("description", "Maintenance " + TestData.unique());
        body.put("amount", amount);
        body.put("dueDate", due.toString());
        ApiClient.Response response = call(session, "POST", INVOICES, body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    protected ApiClient.Response pay(Session session, UUID invoiceId, String amount, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("method", "UPI");
        body.put("reference", "UTR" + TestData.unique());
        return key == null
                ? call(session, "POST", INVOICES + "/" + invoiceId + "/payments", body)
                : call(session, "POST", INVOICES + "/" + invoiceId + "/payments", body, "Idempotency-Key", key);
    }

    protected JsonNode payOk(Session session, UUID invoiceId, String amount) {
        ApiClient.Response response = pay(session, invoiceId, amount, null);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json();
    }

    protected JsonNode invoiceView(Session session, UUID invoiceId) {
        ApiClient.Response response = call(session, "GET", INVOICES + "/" + invoiceId, null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    protected BigDecimal money(JsonNode node) {
        return new BigDecimal(node.asString());
    }

    protected ApiClient.BinaryResponse bytesA(String path) {
        return api.getBytes(path, "Authorization", sessionA.bearer());
    }

    /** All the text of a PDF, as a reader would see it. */
    protected String pdfText(byte[] pdf) {
        try {
            var reader = new org.openpdf.text.pdf.PdfReader(pdf);
            var extractor = new org.openpdf.text.pdf.parser.PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) text.append(extractor.getTextFromPage(page)).append('\n');
            return text.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    protected void setUpi(Session session) {
        ApiClient.Response response = call(session, "PATCH", "/api/v1/community/settings", Map.of("upiId", "lotus.residents@okhdfcbank", "upiPayeeName", "Lotus Residents Welfare"));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
    }

    protected List<Map<String, Object>> outbox(String to, String template) {
        return jdbc.queryForList("select * from email_outbox where to_email = ? and template = ? order by created_at", to, template);
    }
}
