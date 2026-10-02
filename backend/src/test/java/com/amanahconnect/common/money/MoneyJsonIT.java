package com.amanahconnect.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Money over real HTTP: strings out, strict validation in. */
class MoneyJsonIT extends AbstractTenantIT {

    private static final String PATH = "/api/v1/community/test-support/money";

    @Test
    void amountsAreWrittenAsJsonStringsNeverNumbers() {
        ApiClient.Response response = asA("GET", PATH, null);

        assertThat(response.body()).contains("\"money\":\"1250.50\"").contains("\"decimal\":\"1000\"");
        assertThat(response.body()).doesNotContain("1E+3").doesNotMatch(".*\"money\":[0-9].*");
    }

    @Test
    void acceptsAStringOrANumberAndEchoesTwoDecimals() {
        ApiClient.Response fromString = asA("POST", PATH, Map.of("amount", "10.5"));
        ApiClient.Response fromNumber = asA("POST", PATH, Map.of("amount", 99));

        assertThat(fromString.status()).as(fromString.body()).isEqualTo(200);
        assertThat(fromString.body()).contains("\"money\":\"10.50\"").contains("\"decimal\":\"10.5\"");
        assertThat(fromNumber.body()).contains("\"money\":\"99.00\"");
    }

    @Test
    void rejectsMoreThanTwoDecimalsInsteadOfRoundingWhatThePersonTyped() {
        ApiClient.Response response = asA("POST", PATH, Map.of("amount", "10.505"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().get("errors").toString()).contains("amount").contains("at most 2 decimals");
    }

    @Test
    void rejectsZeroNegativeHugeAndGarbageAmounts() {
        assertThat(asA("POST", PATH, Map.of("amount", "0")).json().get("errors").toString()).contains("greater than zero");
        assertThat(asA("POST", PATH, Map.of("amount", "-5")).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(asA("POST", PATH, Map.of("amount", "1000000000000")).json().get("errors").toString()).contains("too large");
        ApiClient.Response garbage = asA("POST", PATH, Map.of("amount", "abc"));
        assertThat(garbage.status()).isEqualTo(400);
        assertThat(garbage.body()).doesNotContain("NumberFormat").doesNotContain("java.math");
    }

    @Test
    void validatesStringAmountsToo() {
        ApiClient.Response response = asA("POST", PATH, Map.of("amount", "5", "text", "-1"));

        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().get("errors").toString()).contains("text").contains("must not be negative");
    }

    @Test
    void anAmountIsRequired() {
        ApiClient.Response response = asA("POST", PATH, Map.of("text", "5"));

        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().get("errors").toString()).contains("amount");
    }
}
