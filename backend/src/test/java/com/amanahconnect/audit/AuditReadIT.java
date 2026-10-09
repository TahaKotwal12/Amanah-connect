package com.amanahconnect.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractDeskIT;
import com.amanahconnect.support.ApiClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AuditReadIT extends AbstractDeskIT {

    private static final String COMMUNITY_TRAIL = "/api/v1/community/audit";
    private static final String ADMIN_TRAIL = ADMIN + "/audit";

    private List<String> actions(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("action").asString()));
        return out;
    }

    private JsonNode ok(ApiClient.Response r) {
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    // ---- the community's own trail -------------------------------------------------------------------------------------------

    @Test
    void aCommunityReadsItsOwnTrailInPlainLanguageAndNeverAnotherCommunitys() {
        UUID m = memberA("Trail Member");
        invoiceA(m, "500.00", TODAY.plusDays(5));
        memberB();

        JsonNode page = ok(asA("GET", COMMUNITY_TRAIL, null));

        assertThat(page.get("items").size()).isGreaterThanOrEqualTo(2);
        page.get("items").forEach(i -> {
            assertThat(i.get("summary").asString()).isNotBlank();
            assertThat(i.has("ip")).as("no addresses for communities").isFalse();
            assertThat(i.has("userAgent")).isFalse();
            assertThat(i.has("requestId")).isFalse();
            assertThat(i.has("communityId")).as("the community is implied").isFalse();
        });
        assertThat(actions(page)).contains("INVOICE_CREATED");
        // newest first
        List<String> times = new ArrayList<>();
        page.get("items").forEach(i -> times.add(i.get("at").asString()));
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ok(asB("GET", COMMUNITY_TRAIL, null)).get("items").toString()).doesNotContain(m.toString());
        assertThat(page.toString()).doesNotContain(communityB.getId().toString());
    }

    private void memberB() {
        member(sessionB, "B Member", email(), true);
        invoice(sessionB, member(sessionB, "B Payer", email(), true), "700.00", TODAY.plusDays(3));
    }

    @Test
    void theCommunityTrailIsReadOnly() {
        assertThat(asA("POST", COMMUNITY_TRAIL, Map.of()).status()).isIn(404, 405);
        assertThat(asA("DELETE", COMMUNITY_TRAIL, null).status()).isIn(404, 405);
    }

    // ---- filters -------------------------------------------------------------------------------------------------------------

    @Test
    void filtersNarrowByActionPrefixEntityActorAndDates() {
        UUID m = memberA("Filter Member");
        JsonNode invoice = invoiceA(m, "500.00", TODAY.plusDays(5));
        UUID invoiceId = id(invoice);
        payOk(sessionA, invoiceId, "100.00");

        List<String> payments = actions(ok(asA("GET", COMMUNITY_TRAIL + "?actionPrefix=PAYMENT_", null)));
        assertThat(payments).isNotEmpty().allMatch(a -> a.startsWith("PAYMENT_"));

        List<String> exact = actions(ok(asA("GET", COMMUNITY_TRAIL + "?action=INVOICE_CREATED", null)));
        assertThat(exact).isNotEmpty().containsOnly("INVOICE_CREATED");

        JsonNode byEntity = ok(asA("GET", COMMUNITY_TRAIL + "?entityType=Invoice&entityId=" + invoiceId, null));
        assertThat(byEntity.get("items").size()).isGreaterThanOrEqualTo(1);
        byEntity.get("items").forEach(i -> assertThat(i.get("entityId").asString()).isEqualTo(invoiceId.toString()));

        assertThat(ok(asA("GET", COMMUNITY_TRAIL + "?actor=" + adminA.id(), null)).get("items").size()).isGreaterThan(0);
        assertThat(ok(asA("GET", COMMUNITY_TRAIL + "?actor=" + UUID.randomUUID(), null)).get("items")).isEmpty();

        assertThat(ok(asA("GET", COMMUNITY_TRAIL + "?from=" + TODAY + "&to=" + TODAY, null)).get("items").size()).isGreaterThan(0);
        assertThat(ok(asA("GET", COMMUNITY_TRAIL + "?from=" + TODAY.plusDays(1), null)).get("items")).isEmpty();
        assertThat(ok(asA("GET", COMMUNITY_TRAIL + "?to=" + TODAY.minusDays(1), null)).get("items")).isEmpty();
    }

    @Test
    void malformedFiltersAreRefusedWithAStableCode() {
        for (String bad : List.of("from=yesterday", "to=2026-13-45", "action=DROP%20TABLE", "limit=0", "limit=500", "actor=not-a-uuid")) {
            ApiClient.Response r = asA("GET", COMMUNITY_TRAIL + "?" + bad, null);
            assertThat(r.status()).as(bad).isEqualTo(400);
        }
        ApiClient.Response backwards = asA("GET", COMMUNITY_TRAIL + "?from=2026-05-10&to=2026-05-01", null);
        assertThat(backwards.status()).isEqualTo(400);
        assertThat(backwards.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(asA("GET", COMMUNITY_TRAIL + "?cursor=garbage", null).status()).isEqualTo(400);
    }

    // ---- keyset pagination -----------------------------------------------------------------------------------------------------

    @Test
    void pagesWalkEveryRowOnceEvenWhileNewRowsArrive() {
        for (int i = 0; i < 7; i++) memberA("Page Member " + i);
        int total = ok(asA("GET", COMMUNITY_TRAIL + "?actionPrefix=MEMBER_&limit=200", null)).get("items").size();
        assertThat(total).isGreaterThanOrEqualTo(7);

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        boolean injected = false;
        do {
            String url = COMMUNITY_TRAIL + "?actionPrefix=MEMBER_&limit=3" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = ok(asA("GET", url, null));
            assertThat(page.get("items").size()).isLessThanOrEqualTo(3);
            page.get("items").forEach(i -> assertThat(seen.add(i.get("id").asString())).as("no row twice").isTrue());
            if (!injected) {
                memberA("Arrived Mid Walk"); // newer than the cursor: must not shift or repeat anything
                injected = true;
            }
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            assertThat(page.get("hasMore").asBoolean()).isEqualTo(cursor != null);
            pages++;
        } while (cursor != null && pages < 50);

        assertThat(seen).hasSize(total);
        assertThat(pages).isEqualTo((total + 2) / 3);
    }

    // ---- the platform's view ---------------------------------------------------------------------------------------------------

    @Test
    void platformStaffSearchAcrossCommunitiesAndFilterByOne() {
        memberA("Admin View A");
        member(sessionB, "Admin View B", email(), true);

        JsonNode all = ok(asSuper("GET", ADMIN_TRAIL + "?actionPrefix=MEMBER_&limit=200", null));
        Set<String> communities = new HashSet<>();
        all.get("items").forEach(i -> communities.add(i.get("communityId").asString()));
        assertThat(communities).contains(communityA.getId().toString(), communityB.getId().toString());

        JsonNode onlyA = ok(asSuper("GET", ADMIN_TRAIL + "?communityId=" + communityA.getId() + "&limit=200", null));
        assertThat(onlyA.get("items")).isNotEmpty();
        onlyA.get("items").forEach(i -> {
            assertThat(i.get("communityId").asString()).isEqualTo(communityA.getId().toString());
            assertThat(i.has("ip")).isTrue();
            assertThat(i.has("communityName")).isTrue();
        });
    }

    @Test
    void onlyPlatformStaffMayUseTheAdminTrail() {
        assertThat(asA("GET", ADMIN_TRAIL, null).status()).isEqualTo(403);
        assertThat(asA("GET", ADMIN_TRAIL + "/export", null).status()).isEqualTo(403);
        assertThat(api.get(ADMIN_TRAIL).status()).isEqualTo(401);
    }

    // ---- CSV export ------------------------------------------------------------------------------------------------------------

    @Test
    void theCsvExportIsFilteredStreamedAndItselfAudited() {
        memberA("Csv Member");
        memberA("Csv Member Two");

        ApiClient.Response csv = asSuper("GET", ADMIN_TRAIL + "/export?communityId=" + communityA.getId() + "&actionPrefix=MEMBER_", null);

        assertThat(csv.status()).as(csv.body()).isEqualTo(200);
        assertThat(csv.header("Content-Type")).startsWith("text/csv");
        assertThat(csv.header("Content-Disposition")).contains("attachment").contains("audit-");
        assertThat(csv.header("Cache-Control")).contains("no-store");
        assertThat(csv.header("X-Export-Truncated")).isEqualTo("false");
        String[] lines = csv.body().strip().split("\r?\n");
        assertThat(lines.length - 1).as("one row per entry plus the header").isEqualTo(Integer.parseInt(csv.header("X-Export-Rows")));
        assertThat(lines[0]).contains("action").contains("time_utc");
        assertThat(csv.body()).contains("MEMBER_CREATED");

        JsonNode exported = ok(asSuper("GET", ADMIN_TRAIL + "?action=AUDIT_EXPORTED", null));
        assertThat(exported.get("items")).isNotEmpty();
        JsonNode entry = exported.get("items").get(0);
        assertThat(entry.get("actorId").asString()).isEqualTo(superAdmin.id().toString());
        assertThat(entry.get("after").toString()).contains("rows");
        assertThat(csv.body()).as("an export never contains its own audit entry").doesNotContain(entry.get("id").asString());
    }

    @Test
    void csvCellsThatLookLikeFormulasAreNeutralised() {
        // a member name starting with '=' ends up in the audit "after" payload only as ids/counts, so check via a reason that is stored verbatim
        UUID m = memberA("Formula Member");
        JsonNode inv = invoiceA(m, "500.00", TODAY.plusDays(5));
        asA("POST", INVOICES + "/" + id(inv) + "/cancel", Map.of("reason", "=HYPERLINK(\"http://evil.test\")"));

        ApiClient.Response csv = asSuper("GET", ADMIN_TRAIL + "/export?communityId=" + communityA.getId() + "&action=INVOICE_CANCELLED", null);

        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.body()).doesNotContain(",=HYPERLINK").doesNotContain(",\"=HYPERLINK");
    }

    @Test
    void theAuditTrailCannotBeChangedThroughTheApi() {
        assertThat(asSuper("DELETE", ADMIN_TRAIL, null).status()).isIn(404, 405);
        assertThat(asSuper("POST", ADMIN_TRAIL, Map.of()).status()).isIn(404, 405);
        assertThat(asSuper("PUT", ADMIN_TRAIL + "/" + UUID.randomUUID(), Map.of()).status()).isIn(404, 405);
    }
}
