package com.amanahconnect.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.amanahconnect.dataexport.DataExportService;

/** Cross-tenant checks for the read-side endpoints: dashboard, audit trail, data exports and erasure. */
class ReadSideIsolationIT extends AbstractTenantIT {

    @Autowired DataExportService exports;

    private static UUID id(tools.jackson.databind.JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    @Test
    void dashboardIsOneViewPerCommunityAndIgnoresForgedTenantInput() {
        UUID mb = id(asB("POST", "/api/v1/community/members", Map.of("fullName", "Dash B", "consentEmail", false)).json());

        ApiClient.Response a = assertTenantSingleton("GET", "/api/v1/community/dashboard", null, () -> asB("GET", "/api/v1/community/dashboard", null).json().get("members").toString());

        assertThat(a.json().get("members").get("total").asLong()).as("A does not see B's member " + mb).isZero();
        assertThat(asB("GET", "/api/v1/community/dashboard", null).json().get("members").get("total").asLong()).isEqualTo(1);
    }

    @Test
    void auditTrailListsOnlyTheCallersCommunity() {
        UUID mb = id(asB("POST", "/api/v1/community/members", Map.of("fullName", "Audit B", "consentEmail", false)).json());
        String entryOfB = asB("GET", "/api/v1/community/audit?actionPrefix=MEMBER_", null).json().get("items").get(0).get("id").asString();

        assertListHides("/api/v1/community/audit", UUID.fromString(entryOfB));
        assertListHides("/api/v1/community/audit?entityId=" + mb, UUID.fromString(entryOfB));
        assertThat(asA("GET", "/api/v1/community/audit?communityId=" + communityB.getId() + "&entityId=" + mb, null).json().get("items")).isEmpty();
    }

    @Test
    void dataExportsAreScopedAndTheListHidesOtherCommunities() {
        ApiClient.Response created = asB("POST", "/api/v1/community/data-exports", null);
        assertThat(created.status()).isEqualTo(202);
        UUID exportOfB = id(created.json());
        exports.work();

        assertListHides("/api/v1/community/data-exports", exportOfB);
        assertCrossTenantRead("/api/v1/community/data-exports/" + exportOfB);
        assertCrossTenantRead("/api/v1/community/data-exports/" + exportOfB + "/download-url");
        assertCreateCannotTargetOtherTenant("/api/v1/community/data-exports", Map.of(), r -> id(r.json()), "/api/v1/community/data-exports/%s");
    }

    @Test
    void anonymisingAnotherCommunitysMemberIsNotFoundAndChangesNothing() {
        UUID mb = id(asB("POST", "/api/v1/community/members", Map.of("fullName", "Keep B", "consentEmail", false)).json());
        String no = asB("GET", "/api/v1/community/members/" + mb, null).json().get("memberNo").asString();
        Map<String, Object> body = Map.of("memberNo", no, "reason", "isolation check");

        assertCrossTenantUpdate("POST", "/api/v1/community/members/" + mb + "/anonymise", body,
                () -> assertThat(jdbc.queryForObject("select full_name from members where id = ?", String.class, mb)).isEqualTo("Keep B"));
    }
}
