package com.amanahconnect.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PaymentFlowIT extends AbstractFinanceIT {

    @Test
    void invoiceToPaymentToReceiptToLedger() {
        UUID member = memberA("Asha Rao");
        JsonNode invoice = invoiceA(member, "1500.00", TODAY.plusDays(5));
        UUID invoiceId = id(invoice);
        assertThat(invoice.get("status").asString()).isEqualTo("ISSUED");
        assertThat(invoice.get("invoiceNo").asString()).startsWith("INV-");

        JsonNode partial = payOk(sessionA, invoiceId, "500.00");
        assertThat(partial.get("invoice").get("status").asString()).isEqualTo("PARTIAL");
        assertThat(partial.get("invoice").get("balance").asString()).isEqualTo("1000.00");
        assertThat(partial.get("payment").get("receiptNo").asString()).startsWith("RCP-");

        JsonNode full = payOk(sessionA, invoiceId, "1000.00");
        assertThat(full.get("invoice").get("status").asString()).isEqualTo("PAID");
        assertThat(count("select count(*) from ledger_entries where community_id = ? and source = 'PAYMENT'", communityA.getId())).isEqualTo(2);

        ApiClient.Response pdf = asA("GET", RECEIPTS + "/" + full.get("payment").get("receiptId").asString() + "/pdf", null);
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.header("Content-Type")).startsWith("application/pdf");
        assertThat(pdf.body()).startsWith("%PDF");
    }
}
