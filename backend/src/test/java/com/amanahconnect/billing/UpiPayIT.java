package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.auth.Tokens;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class UpiPayIT extends AbstractFinanceIT {

    private static final String PUBLIC = "/api/v1/public/pay/";

    private String decode(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
    }

    private String tokenOf(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private String newToken(UUID invoiceId) {
        ApiClient.Response response = asA("POST", INVOICES + "/" + invoiceId + "/pay-link", null);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return tokenOf(response.json().get("url").asString());
    }

    // ---- the QR code ---------------------------------------------------------------------------------------------------

    @Test
    void theQrCodeEncodesTheStandardUpiLinkForTheBalance() throws Exception {
        setUpi(sessionA);
        JsonNode invoice = invoiceA(memberA("Asha"), "1500.00", TODAY.plusDays(5));
        UUID invoiceId = id(invoice);
        String note = UpiLinks.encode(invoice.get("invoiceNo").asString());

        ApiClient.BinaryResponse qr = bytesA(INVOICES + "/" + invoiceId + "/qr");

        assertThat(qr.status()).isEqualTo(200);
        assertThat(qr.header("Content-Type")).isEqualTo("image/png");
        assertThat(qr.header("Cache-Control")).contains("no-store");
        assertThat(qr.body()).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        String expected = "upi://pay?pa=lotus.residents@okhdfcbank&pn=Lotus%20Residents%20Welfare&am=1500.00&cu=INR&tn=" + note;
        assertThat(decode(qr.body())).isEqualTo(expected);

        payOk(sessionA, invoiceId, "600.00");
        assertThat(decode(bytesA(INVOICES + "/" + invoiceId + "/qr").body())).as("after a part payment it asks for what is left").isEqualTo(expected.replace("am=1500.00", "am=900.00"));
    }

    @Test
    void theQrCodeNeedsUpiSetUpAPayableInvoiceAndThePlanFeature() {
        UUID invoiceId = id(invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(5)));

        ApiClient.Response noUpi = asA("GET", INVOICES + "/" + invoiceId + "/qr", null);
        assertThat(noUpi.status()).isEqualTo(409);
        assertThat(noUpi.code()).isEqualTo("UPI_NOT_CONFIGURED");

        setUpi(sessionA);
        assertThat(bytesA(INVOICES + "/" + invoiceId + "/qr").status()).isEqualTo(200);

        payOk(sessionA, invoiceId, "100.00");
        ApiClient.Response paid = asA("GET", INVOICES + "/" + invoiceId + "/qr", null);
        assertThat(paid.status()).isEqualTo(409);
        assertThat(paid.code()).isEqualTo("INVOICE_NOT_PAYABLE");

        jdbc.update("update communities set currency = 'USD' where id = ?", communityA.getId());
        UUID other = id(invoiceA(memberA("Ravi"), "100.00", TODAY.plusDays(5)));
        ApiClient.Response usd = asA("GET", INVOICES + "/" + other + "/qr", null);
        assertThat(usd.status()).isEqualTo(409);
        assertThat(usd.json().toString()).contains("rupees");
        jdbc.update("update communities set currency = 'INR' where id = ?", communityA.getId());

        Plan noQr = data.customPlan("No QR", Map.of(), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", noQr.getId(), communityA.getId());
        ApiClient.Response feature = asA("GET", INVOICES + "/" + other + "/qr", null);
        assertThat(feature.status()).isEqualTo(402);
        assertThat(feature.code()).isEqualTo("PLAN_FEATURE_UNAVAILABLE");
        assertThat(asA("POST", INVOICES + "/" + other + "/pay-link", null).status()).isEqualTo(402);
    }

    // ---- pay links and the public page -------------------------------------------------------------------------------------

    @Test
    void aPayLinkIsUnguessableExpiringAndStoredOnlyAsAHash() {
        setUpi(sessionA);
        UUID invoiceId = id(invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(5)));

        ApiClient.Response created = asA("POST", INVOICES + "/" + invoiceId + "/pay-link", null);

        String url = created.json().get("url").asString();
        String token = tokenOf(url);
        assertThat(url).startsWith("http://localhost:5173/pay/");
        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(java.time.Instant.parse(created.json().get("expiresAt").asString())).isBetween(java.time.Instant.now().plus(59, java.time.temporal.ChronoUnit.DAYS), java.time.Instant.now().plus(61, java.time.temporal.ChronoUnit.DAYS));
        assertThat(count("select count(*) from payment_links where invoice_id = ? and token_hash = ?", invoiceId, Tokens.sha256Hex(token))).as("only the hash is stored").isOne();
        assertThat(count("select count(*) from payment_links where token_hash = ?", token)).isZero();
        assertThat(newToken(invoiceId)).as("every link is different").isNotEqualTo(token);
        assertThat(count("select count(*) from audit_logs where action = 'PAY_LINK_CREATED' and entity_id = ?", invoiceId)).isEqualTo(2);
        assertThat(asA("POST", INVOICES + "/" + invoiceId + "/pay-link", null).body()).doesNotContain("tokenHash");
    }

    @Test
    void thePublicPageShowsOneInvoiceAndSaysConfirmationIsManual() throws Exception {
        setUpi(sessionA);
        String logoKey = "communities/" + communityA.getId() + "/logo/" + UUID.randomUUID() + ".png";
        storage.put(logoKey, "image/png", 100);
        jdbc.update("update communities set logo_key = ? where id = ?", logoKey, communityA.getId());
        UUID member = member(sessionA, "Asha Rao", "asha.private@example.test", true);
        asA("PATCH", "/api/v1/community/members/" + member, Map.of("phone", "+91 98765 43210"));
        JsonNode invoice = invoiceA(member, "1500.00", TODAY.plusDays(5));
        invoiceA(member, "999.00", TODAY.plusDays(5)); // another invoice of the same member
        String token = newToken(id(invoice));

        ApiClient.Response page = api.get(PUBLIC + token);

        assertThat(page.status()).as(page.body()).isEqualTo(200);
        assertThat(page.header("Cache-Control")).contains("no-store");
        JsonNode view = page.json();
        assertThat(view.get("communityName").asString()).isEqualTo(communityA.getName());
        assertThat(view.get("logoUrl").asString()).contains(logoKey);
        assertThat(view.get("invoiceNo").asString()).isEqualTo(invoice.get("invoiceNo").asString());
        assertThat(view.get("currency").asString()).isEqualTo("INR");
        assertThat(view.get("amount").asString()).isEqualTo("1500.00");
        assertThat(view.get("amountPaid").asString()).isEqualTo("0.00");
        assertThat(view.get("balanceDue").asString()).isEqualTo("1500.00");
        assertThat(view.get("dueDate").asString()).isEqualTo(TODAY.plusDays(5).toString());
        assertThat(view.get("status").asString()).isEqualTo("ISSUED");
        assertThat(view.get("payable").asBoolean()).isTrue();
        assertThat(view.get("manualConfirmation").asBoolean()).isTrue();
        assertThat(view.get("notice").asString()).contains("does not update the bill by itself").contains("admin confirms");
        JsonNode upi = view.get("upi");
        assertThat(upi.get("payeeName").asString()).isEqualTo("Lotus Residents Welfare");
        String expected = "upi://pay?pa=lotus.residents@okhdfcbank&pn=Lotus%20Residents%20Welfare&am=1500.00&cu=INR&tn=" + UpiLinks.encode(invoice.get("invoiceNo").asString());
        assertThat(upi.get("link").asString()).isEqualTo(expected);
        assertThat(decode(Base64.getDecoder().decode(upi.get("qrCodePngBase64").asString()))).isEqualTo(expected);

        // nothing about the payer or anyone else
        String withoutLogo = page.body().replace(view.get("logoUrl").asString(), "");
        assertThat(withoutLogo).doesNotContain("Asha").doesNotContain("asha.private").doesNotContain("98765").doesNotContain("999.00")
                .doesNotContain(communityA.getId().toString()).doesNotContain(member.toString()).doesNotContain(communityB.getName()).doesNotContain(adminA.email());
        List<String> keys = new java.util.ArrayList<>();
        view.properties().forEach(e -> keys.add(e.getKey()));
        assertThat(keys).containsExactlyInAnyOrder("communityName", "logoUrl", "invoiceNo", "description", "period", "currency", "amount", "amountPaid", "balanceDue", "dueDate", "status", "payable", "upi", "manualConfirmation", "notice");
    }

    @Test
    void thePageFollowsTheInvoiceAsItIsPaid() {
        setUpi(sessionA);
        UUID invoiceId = id(invoiceA(memberA("Asha"), "1000.00", TODAY.plusDays(5)));
        String token = newToken(invoiceId);

        payOk(sessionA, invoiceId, "250.50");
        JsonNode partial = api.get(PUBLIC + token).json();
        assertThat(partial.get("amountPaid").asString()).isEqualTo("250.50");
        assertThat(partial.get("balanceDue").asString()).isEqualTo("749.50");
        assertThat(partial.get("status").asString()).isEqualTo("PARTIAL");
        assertThat(partial.get("upi").get("link").asString()).contains("am=749.50");

        payOk(sessionA, invoiceId, "749.50");
        JsonNode paid = api.get(PUBLIC + token).json();
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("payable").asBoolean()).isFalse();
        assertThat(paid.get("upi").isNull()).as("nothing left to pay").isTrue();
        assertThat(paid.get("balanceDue").asString()).isEqualTo("0.00");
    }

    @Test
    void withoutAUpiIdThePageStillInformsButOffersNoPayment() {
        UUID invoiceId = id(invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(5)));
        setUpi(sessionA);
        String token = newToken(invoiceId);
        jdbc.update("update communities set upi_id = null where id = ?", communityA.getId());

        JsonNode view = api.get(PUBLIC + token).json();

        assertThat(view.get("payable").asBoolean()).isTrue();
        assertThat(view.get("upi").isNull()).isTrue();
        assertThat(view.get("manualConfirmation").asBoolean()).isTrue();
    }

    @Test
    void everyKindOfBadLinkGetsTheSameAnswer() {
        setUpi(sessionA);
        UUID expired = id(invoiceA(memberA("Asha"), "100.00", TODAY.plusDays(5)));
        String expiredToken = newToken(expired);
        jdbc.update("update payment_links set expires_at = now() - interval '1 second' where invoice_id = ?", expired);
        UUID cancelled = id(invoiceA(memberA("Ravi"), "100.00", TODAY.plusDays(5)));
        String cancelledToken = newToken(cancelled);
        asA("POST", INVOICES + "/" + cancelled + "/cancel", Map.of("reason", "x"));
        UUID draftMember = memberA("Draft");
        Map<String, Object> draftBody = new java.util.LinkedHashMap<>(Map.<String, Object>of("memberId", draftMember.toString(), "kind", "FINE", "description", "d", "amount", "5.00", "dueDate", TODAY.plusDays(2).toString(), "draft", true));
        UUID draft = id(asA("POST", INVOICES, draftBody).json());
        jdbc.update("insert into payment_links (id, community_id, invoice_id, token_hash, expires_at) values (gen_random_uuid(), ?, ?, ?, now() + interval '1 day')", communityA.getId(), draft, Tokens.sha256Hex("D".repeat(43)));
        UUID suspended = id(invoiceA(memberA("Sunita"), "100.00", TODAY.plusDays(5)));
        String suspendedToken = newToken(suspended);
        UUID revoked = id(invoiceA(memberA("Revoked"), "100.00", TODAY.plusDays(5)));
        String revokedToken = newToken(revoked);
        jdbc.update("update payment_links set revoked_at = now() where invoice_id = ?", revoked);

        ApiClient.Response reference = api.get(PUBLIC + "A".repeat(43));
        assertThat(reference.status()).isEqualTo(404);
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());
        for (String token : List.of(expiredToken, cancelledToken, "D".repeat(43), suspendedToken, revokedToken, "short", "x".repeat(200), "A".repeat(43), "a b", expiredToken.toUpperCase())) {
            ApiClient.Response response = api.get(PUBLIC + token.replace(" ", "%20"));
            assertThat(response.status()).as(token).isEqualTo(404);
            assertThat(response.code()).as(token).isEqualTo("PAY_LINK_UNAVAILABLE");
            assertThat(response.json().get("detail")).as(token).isEqualTo(reference.json().get("detail"));
            assertThat(response.body()).doesNotContain(communityA.getName());
        }
    }

    @Test
    void theBillEmailLinkOpensThePage() {
        setUpi(sessionA);
        String address = email();
        member(sessionA, "Payer", address, true);
        JsonNode plan = feePlan(sessionA, "Monthly", "700.00", "MONTHLY", Map.of());
        generate(sessionA, id(plan), null);

        String payload = outbox(address, "member-bill").get(0).get("payload").toString();
        String token = payload.substring(payload.indexOf("/pay/") + 5, payload.indexOf("/pay/") + 5 + 43);

        JsonNode view = api.get(PUBLIC + token).json();
        assertThat(view.get("amount").asString()).isEqualTo("700.00");
        assertThat(view.get("invoiceNo").asString()).startsWith("INV-");
    }

    @Test
    void aLinkOnlyEverShowsItsOwnInvoice() {
        setUpi(sessionA);
        UUID member = memberA("Asha");
        UUID first = id(invoiceA(member, "111.00", TODAY.plusDays(5)));
        UUID second = id(invoiceA(member, "222.00", TODAY.plusDays(5)));
        String t1 = newToken(first);
        String t2 = newToken(second);

        assertThat(api.get(PUBLIC + t1).json().get("amount").asString()).isEqualTo("111.00");
        assertThat(api.get(PUBLIC + t2).json().get("amount").asString()).isEqualTo("222.00");
        assertThat(api.get(PUBLIC + t1 + "/extra").status()).isEqualTo(404);
        assertThat(api.get(PUBLIC + t1 + "?invoiceId=" + second).json().get("amount").asString()).as("nothing the visitor sends changes which invoice").isEqualTo("111.00");
    }
}
