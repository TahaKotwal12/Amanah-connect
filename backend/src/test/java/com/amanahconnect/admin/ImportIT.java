package com.amanahconnect.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.AbstractAdminIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.TestData;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ImportIT extends AbstractAdminIT {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private static final String MEMBERS_HEADER = "member_no,full_name,email,phone,group,status,joined_on,consent_email\n";

    private String path(UUID community) {
        return ADMIN + "/communities/" + community + "/import";
    }

    private byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private ApiClient.Response upload(UUID community, UUID batchId, Map<String, byte[]> files) {
        return api.postMultipart(path(community), Map.of("batchId", batchId.toString()), files, "Authorization", ApiClient.bearer(superToken));
    }

    private ApiClient.Response confirm(UUID community, UUID batchId, Object body) {
        return admin("POST", path(community) + "/" + batchId + "/confirm", body);
    }

    private Map<String, byte[]> files(String members, String balances, String invoices) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (members != null) files.put("members", csv(members));
        if (balances != null) files.put("openingBalances", csv(balances));
        if (invoices != null) files.put("openInvoices", csv(invoices));
        return files;
    }

    private long count(String table, UUID community) {
        return jdbc.queryForObject("select count(*) from " + table + " where community_id = ?", Long.class, community);
    }

    private String uniq() {
        return TestData.unique();
    }

    /** A pending community created through the API (it has categories and an owner). */
    private UUID newCommunity() {
        return createCommunityViaApi("Import " + uniq());
    }

    private String incomeCategory(UUID community) {
        return jdbc.queryForObject("select name from ledger_categories where community_id = ? and type = 'INCOME' and active order by name limit 1", String.class, community);
    }

    private String expenseCategory(UUID community) {
        return jdbc.queryForObject("select name from ledger_categories where community_id = ? and type = 'EXPENSE' and active order by name limit 1", String.class, community);
    }

    // ---- dry run ------------------------------------------------------------------------------------------

    @Test
    void aDryRunValidatesEveryRowReportsReasonsAndWritesNothing() {
        UUID community = newCommunity();
        String members = MEMBERS_HEADER
                + "M1,Asha Rao,asha@example.test,+91 98765 43210,Block A,ACTIVE,2020-04-01,yes\n"
                + "M2,,bad@example.test,,,,,\n"
                + "M1,Duplicate No,,,,,,\n"
                + "M3,Ravi Kumar,not-an-email,,,,,\n"
                + "M4,Sunita,,,,PAUSED,31/02/2020,maybe\n";

        ApiClient.Response response = upload(community, UUID.randomUUID(), files(members, null, null));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode report = response.json();
        assertThat(report.get("status").asString()).isEqualTo("DRY_RUN");
        assertThat(report.get("confirmable").asBoolean()).isTrue();
        JsonNode file = report.get("members");
        assertThat(file.get("totalRows").asInt()).isEqualTo(5);
        assertThat(file.get("validRows").asInt()).isEqualTo(1);
        assertThat(file.get("invalidRows").asInt()).isEqualTo(4);
        assertThat(file.get("valid").get(0).get("key").asString()).isEqualTo("M1");
        assertThat(file.get("valid").get(0).get("row").asInt()).as("spreadsheet row, header is row 1").isEqualTo(2);

        Map<Integer, String> reasons = new LinkedHashMap<>();
        file.get("invalid").forEach(r -> reasons.put(r.get("row").asInt(), r.get("errors").toString()));
        assertThat(reasons.get(3)).contains("full_name is required");
        assertThat(reasons.get(4)).contains("member_no M1 is repeated").contains("row 2");
        assertThat(reasons.get(5)).contains("email is not a valid address");
        assertThat(reasons.get(6)).contains("status must be ACTIVE or INACTIVE").contains("joined_on is not a date").contains("consent_email must be yes/no");
        assertThat(report.get("summary").get("validRows").asInt()).isEqualTo(1);
        assertThat(report.get("summary").get("invalidRows").asInt()).isEqualTo(4);

        assertThat(count("members", community)).as("a dry run changes nothing").isZero();
        assertThat(count("ledger_entries", community)).isZero();
        assertThat(count("invoices", community)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_IMPORT_DRY_RUN' and community_id = ?", Long.class, community)).isOne();
    }

    @Test
    void acceptsBomSemicolonsAliasesAndAllDateFormats() {
        UUID community = newCommunity();
        String members = "﻿Member Number;Name;Mobile;Group;Joined;Email Consent;Unknown Thing\n"
                + "A1;Asha;+91 98765 43210;Block A;2020-04-01;yes;x\n"
                + "A2;Bina;;;01/05/2021;no;x\n"
                + "A3;Chitra;;;01-06-2022;TRUE;x\n";

        ApiClient.Response response = upload(community, UUID.randomUUID(), files(members, null, null));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json().get("members").get("validRows").asInt()).isEqualTo(3);
        assertThat(response.json().get("warnings").toString()).contains("unknown_thing");
    }

    @Test
    void reportsMissingColumnsAsBlockingErrors() {
        UUID community = newCommunity();

        ApiClient.Response response = upload(community, UUID.randomUUID(), files("full_name,email\nAsha,a@example.test\n", null, null));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("confirmable").asBoolean()).isFalse();
        assertThat(response.json().get("blockingErrors").toString()).contains("Missing required column 'member_no'");
    }

    @Test
    void refusesFilesThatAreNotCsv() {
        UUID community = newCommunity();
        byte[] xlsx = {'P', 'K', 3, 4, 20, 0, 0, 0};
        ApiClient.Response excel = upload(community, UUID.randomUUID(), Map.of("members", xlsx));
        assertThat(excel.status()).isEqualTo(400);
        assertThat(excel.json().toString()).contains("Excel");

        byte[] binary = {'a', ',', 'b', '\n', 0, 1, 2};
        assertThat(upload(community, UUID.randomUUID(), Map.of("members", binary)).status()).isEqualTo(400);

        byte[] latin1 = (MEMBERS_HEADER + "M1,José,,,,,,\n").getBytes(StandardCharsets.ISO_8859_1);
        ApiClient.Response notUtf8 = upload(community, UUID.randomUUID(), Map.of("members", latin1));
        assertThat(notUtf8.status()).isEqualTo(400);
        assertThat(notUtf8.json().toString()).contains("UTF-8");

        assertThat(upload(community, UUID.randomUUID(), files("", null, null)).status()).as("empty file").isEqualTo(400);
        assertThat(upload(community, UUID.randomUUID(), Map.of()).status()).as("no files").isEqualTo(400);
        assertThat(count("import_batches", community)).as("rejected uploads are not stored").isZero();
    }

    @Test
    void enforcesRowAndSizeLimits() {
        UUID community = newCommunity();
        StringBuilder rows = new StringBuilder(MEMBERS_HEADER);
        for (int i = 0; i < 5001; i++) rows.append("R").append(i).append(",Name ").append(i).append(",,,,,,\n");
        ApiClient.Response tooManyRows = upload(community, UUID.randomUUID(), files(rows.toString(), null, null));
        assertThat(tooManyRows.status()).isEqualTo(400);
        assertThat(tooManyRows.json().toString()).contains("more than 5000 rows");

        byte[] big = csv(MEMBERS_HEADER + "M1,Name,,," + "x".repeat(2 * 1024 * 1024) + ",,,\n");
        ApiClient.Response tooBig = upload(community, UUID.randomUUID(), Map.of("members", big));
        assertThat(tooBig.status()).isEqualTo(413);
        assertThat(tooBig.code()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void validatesOpeningBalancesAgainstTheCommunitysCategories() {
        UUID community = newCommunity();
        String income = incomeCategory(community);
        String expense = expenseCategory(community);
        String balances = "category,type,amount,as_of,description\n"
                + income + ",INCOME,12500.50,2024-03-31,Brought forward\n"
                + expense + ",EXPENSE,300,31/03/2024,\n"
                + "Nonexistent,INCOME,10,2024-03-31,\n"
                + income + ",EXPENSE,10,2024-03-31,\n"
                + income + ",INCOME,1000.999,2024-03-31,\n"
                + income + ",INCOME,0,2024-03-31,\n"
                + income + ",INCOME,\"1,000\",2024-03-31,\n"
                + income + ",INCOME,10," + TODAY.plusDays(1) + ",\n";

        ApiClient.Response response = upload(community, UUID.randomUUID(), files(null, balances, null));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode file = response.json().get("openingBalances");
        assertThat(file.get("validRows").asInt()).isEqualTo(2);
        assertThat(file.get("invalidRows").asInt()).isEqualTo(6);
        String invalid = file.get("invalid").toString();
        assertThat(invalid).contains("no active INCOME category named 'Nonexistent'").contains("no active EXPENSE category named")
                .contains("not a valid amount").contains("must be greater than zero").contains("as_of is in the future");
    }

    // ---- confirm --------------------------------------------------------------------------------------------

    private static final String BALANCE_HEADER = "category,type,amount,as_of,description\n";
    private static final String INVOICE_HEADER = "member_no,invoice_no,kind,period,amount,due_date\n";

    @Test
    void confirmAppliesMembersBalancesAndOpenInvoicesInOneGo() {
        UUID community = newCommunity();
        String members = MEMBERS_HEADER
                + "M1,Asha Rao,Asha@Example.test,+91 98765 43210,Block A,ACTIVE,2020-04-01,yes\n"
                + "M2,Ravi Kumar,,,,INACTIVE,,\n";
        String balances = BALANCE_HEADER + incomeCategory(community) + ",INCOME,12500.50,2024-03-31,Brought forward\n";
        String invoices = INVOICE_HEADER
                + "M1,OLD-001,MEMBERSHIP,2024-25,1200.00," + TODAY.plusDays(10) + "\n"
                + "M2,OLD-002,DONATION,,300.25," + TODAY.minusDays(20) + "\n";
        UUID batch = UUID.randomUUID();
        assertThat(upload(community, batch, files(members, balances, invoices)).status()).isEqualTo(201);

        ApiClient.Response response = confirm(community, batch, null);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("CONFIRMED");
        JsonNode result = response.json().get("result");
        assertThat(result.get("membersCreated").asInt()).isEqualTo(2);
        assertThat(result.get("openingBalancesCreated").asInt()).isOne();
        assertThat(result.get("openInvoicesCreated").asInt()).isEqualTo(2);
        assertThat(result.get("outstandingInvoiceTotal").asString()).isEqualTo("1500.25");

        Map<String, Object> asha = jdbc.queryForMap("select * from members where community_id = ? and member_no = 'M1'", community);
        assertThat(asha.get("full_name")).isEqualTo("Asha Rao");
        assertThat(asha.get("email").toString()).isEqualTo("Asha@Example.test");
        assertThat(asha.get("group_label")).isEqualTo("Block A");
        assertThat(asha.get("consent_email")).isEqualTo(true);
        assertThat(asha.get("joined_on").toString()).isEqualTo("2020-04-01");
        assertThat(jdbc.queryForObject("select status from members where community_id = ? and member_no = 'M2'", String.class, community)).isEqualTo("INACTIVE");

        Map<String, Object> entry = jdbc.queryForMap("select * from ledger_entries where community_id = ?", community);
        assertThat(entry.get("amount").toString()).isEqualTo("12500.50");
        assertThat(entry.get("type")).isEqualTo("INCOME");
        assertThat(entry.get("source")).isEqualTo("MANUAL");
        assertThat(entry.get("created_by")).isEqualTo(superAdmin.id());
        assertThat(entry.get("entry_date").toString()).isEqualTo("2024-03-31");
        assertThat(entry.get("title")).isEqualTo("Brought forward");

        Map<String, Object> issued = jdbc.queryForMap("select * from invoices where community_id = ? and invoice_no = 'OLD-001'", community);
        assertThat(issued.get("status")).isEqualTo("ISSUED");
        assertThat(issued.get("amount").toString()).isEqualTo("1200.00");
        assertThat(issued.get("amount_paid").toString()).isEqualTo("0.00");
        assertThat(issued.get("kind")).isEqualTo("MEMBERSHIP");
        assertThat(jdbc.queryForObject("select status from invoices where community_id = ? and invoice_no = 'OLD-002'", String.class, community)).isEqualTo("OVERDUE");
        assertThat(jdbc.queryForObject("select count(*) from invoices i join members m on m.id = i.member_id and m.community_id = i.community_id where i.community_id = ? and m.member_no = 'M1'", Long.class, community)).isOne();

        List<Map<String, Object>> audit = jdbc.queryForList("select * from audit_logs where action = 'COMMUNITY_IMPORT_CONFIRMED' and community_id = ?", community);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("actor_user_id")).isEqualTo(superAdmin.id());
        assertThat(audit.get(0).get("after").toString()).contains("membersCreated").doesNotContain("Asha");
    }

    @Test
    void confirmingTwiceReturnsTheStoredResultAndAddsNothing() {
        UUID community = newCommunity();
        UUID batch = UUID.randomUUID();
        upload(community, batch, files(MEMBERS_HEADER + "M1,Asha,,,,,,\n", null, null));

        ApiClient.Response first = confirm(community, batch, null);
        ApiClient.Response second = confirm(community, batch, Map.of("skipInvalidRows", true));

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().get("result")).isEqualTo(first.json().get("result"));
        assertThat(count("members", community)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_IMPORT_CONFIRMED' and community_id = ?", Long.class, community)).isOne();
    }

    @Test
    void concurrentConfirmsApplyTheBatchOnce() throws Exception {
        UUID community = newCommunity();
        UUID batch = UUID.randomUUID();
        upload(community, batch, files(MEMBERS_HEADER + "M1,Asha,,,,,,\nM2,Ravi,,,,,,\n", null, null));

        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> confirm(community, batch, null).status()));
            for (var f : futures) assertThat(f.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("members", community)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_IMPORT_CONFIRMED' and community_id = ?", Long.class, community)).isOne();
    }

    @Test
    void theSameBatchIdWithTheSameFilesIsAReplayButDifferentFilesConflict() {
        UUID community = newCommunity();
        UUID batch = UUID.randomUUID();
        Map<String, byte[]> files = files(MEMBERS_HEADER + "M1,Asha,,,,,,\n", null, null);

        ApiClient.Response first = upload(community, batch, files);
        ApiClient.Response replay = upload(community, batch, files);
        ApiClient.Response different = upload(community, batch, files(MEMBERS_HEADER + "M1,Asha,,,,,,\nM2,Ravi,,,,,,\n", null, null));

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.json()).isEqualTo(first.json());
        assertThat(different.status()).isEqualTo(409);
        assertThat(different.code()).isEqualTo("IMPORT_BATCH_CONFLICT");
        assertThat(count("import_batches", community)).isOne();

        confirm(community, batch, null);
        ApiClient.Response afterConfirm = upload(community, batch, files);
        assertThat(afterConfirm.status()).isEqualTo(200);
        assertThat(afterConfirm.json().get("status").asString()).as("replaying after confirm shows it is done").isEqualTo("CONFIRMED");
        assertThat(count("members", community)).isOne();
    }

    @Test
    void refusesToConfirmWithInvalidRowsUnlessToldToSkipThem() {
        UUID community = newCommunity();
        UUID batch = UUID.randomUUID();
        String members = MEMBERS_HEADER + "M1,Asha,,,,,,\nM2,,,,,,,\n";
        String invoices = INVOICE_HEADER + "M1,OLD-1,,," + "100.00," + TODAY.plusDays(5) + "\nM2,OLD-2,,,50.00," + TODAY.plusDays(5) + "\n";
        ApiClient.Response dry = upload(community, batch, files(members, null, invoices));
        assertThat(dry.json().get("openInvoices").get("invalid").toString()).contains("member on row 3 of the members file, which failed validation");

        ApiClient.Response refused = confirm(community, batch, null);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("IMPORT_NOT_CONFIRMABLE");
        assertThat(count("members", community)).isZero();

        ApiClient.Response skipped = confirm(community, batch, Map.of("skipInvalidRows", true));
        assertThat(skipped.status()).as(skipped.body()).isEqualTo(200);
        assertThat(skipped.json().get("result").get("membersCreated").asInt()).isOne();
        assertThat(skipped.json().get("result").get("openInvoicesCreated").asInt()).isOne();
        assertThat(skipped.json().get("result").get("skippedInvalidRows").asInt()).isEqualTo(2);
        assertThat(count("members", community)).isOne();
        assertThat(count("invoices", community)).isOne();
    }

    @Test
    void invoicesCanReferToExistingMembersAndRejectDuplicatesAndPlatformNumbers() {
        UUID community = newCommunity();
        UUID first = UUID.randomUUID();
        upload(community, first, files(MEMBERS_HEADER + "M1,Asha,,,,,,\n", null, INVOICE_HEADER + "M1,OLD-1,,,100.00," + TODAY.plusDays(5) + "\n"));
        assertThat(confirm(community, first, null).status()).isEqualTo(200);

        String invoices = INVOICE_HEADER
                + "M1,OLD-2,OTHER,,100.00," + TODAY.plusDays(5) + "\n"
                + "M1,OLD-1,,,100.00," + TODAY.plusDays(5) + "\n"
                + "M1,OLD-3,,,100.00," + TODAY.plusDays(5) + "\n"
                + "M1,OLD-3,,,100.00," + TODAY.plusDays(5) + "\n"
                + "GHOST,OLD-4,,,100.00," + TODAY.plusDays(5) + "\n"
                + "M1,INV-2024-25/000007,,,100.00," + TODAY.plusDays(5) + "\n"
                + "M1,OLD-5,BADKIND,,100.00," + TODAY.plusDays(5) + "\n"
                + "M1,OLD-6,,,100.00,\n"
                + "M1,OLD-7,,,-5," + TODAY.plusDays(5) + "\n";
        ApiClient.Response response = upload(community, UUID.randomUUID(), files(null, null, invoices));

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode file = response.json().get("openInvoices");
        assertThat(file.get("validRows").asInt()).as("OLD-2 (kind OTHER) and the first OLD-3").isEqualTo(2);
        String invalid = file.get("invalid").toString();
        assertThat(invalid).contains("invoice_no OLD-1 already exists").contains("invoice_no OLD-3 is repeated").contains("member_no GHOST is not a member")
                .contains("looks like a number this system issues itself").contains("kind must be").contains("due_date is required").contains("amount must be greater than zero");
    }

    // ---- plan limits -----------------------------------------------------------------------------------------

    @Test
    void anImportThatExceedsThePlanMemberLimitIsBlockedAndConfirmAnswers402() {
        Plan plan = data.customPlan("Tiny", Map.of("max_members", 3), Map.of());
        Community community = data.communityOn(plan);
        data.member(community);
        data.member(community);
        UUID batch = UUID.randomUUID();

        ApiClient.Response dry = upload(community.getId(), batch, files(MEMBERS_HEADER + "N1,One,,,,,,\nN2,Two,,,,,,\n", null, null));

        assertThat(dry.status()).isEqualTo(201);
        assertThat(dry.json().get("confirmable").asBoolean()).isFalse();
        assertThat(dry.json().get("blockingErrors").toString()).contains("allows 3 members").contains("1 over the limit");
        assertThat(dry.json().get("summary").get("planMemberLimit").asInt()).isEqualTo(3);
        assertThat(dry.json().get("summary").get("currentMembers").asInt()).isEqualTo(2);

        ApiClient.Response confirm = confirm(community.getId(), batch, null);
        assertThat(confirm.status()).isEqualTo(402);
        assertThat(confirm.code()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(count("members", community.getId())).isEqualTo(2);
    }

    @Test
    void anImportThatExactlyFillsThePlanIsAllowed() {
        Plan plan = data.customPlan("Exact", Map.of("max_members", 3), Map.of());
        Community community = data.communityOn(plan);
        data.member(community);
        UUID batch = UUID.randomUUID();

        ApiClient.Response dry = upload(community.getId(), batch, files(MEMBERS_HEADER + "N1,One,,,,,,\nN2,Two,,,,,,\n", null, null));

        assertThat(dry.json().get("confirmable").asBoolean()).isTrue();
        assertThat(confirm(community.getId(), batch, null).status()).isEqualTo(200);
        assertThat(count("members", community.getId())).isEqualTo(3);
    }

    @Test
    void rowsThatFailValidationDoNotCountTowardsThePlanLimit() {
        Plan plan = data.customPlan("Two", Map.of("max_members", 2), Map.of());
        Community community = data.communityOn(plan);
        ApiClient.Response dry = upload(community.getId(), UUID.randomUUID(), files(MEMBERS_HEADER + "N1,One,,,,,,\nN2,Two,,,,,,\nN3,,,,,,,\n", null, null));
        assertThat(dry.json().get("confirmable").asBoolean()).isTrue();
        assertThat(dry.json().get("summary").get("newMembers").asInt()).isEqualTo(2);
    }

    // ---- state and safety -------------------------------------------------------------------------------------

    @Test
    void failsCleanlyIfTheCommunityChangedBetweenDryRunAndConfirm() {
        Community community = data.communityOn(data.plan("STARTER"));
        UUID batch = UUID.randomUUID();
        upload(community.getId(), batch, files(MEMBERS_HEADER + "M1,Asha,,,,,,\nM2,Ravi,,,,,,\n", null, null));
        jdbc.update("insert into members (id, community_id, member_no, full_name) values (gen_random_uuid(), ?, 'M2', 'Added meanwhile')", community.getId());

        ApiClient.Response response = confirm(community.getId(), batch, null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("IMPORT_CONFLICT");
        assertThat(response.json().toString()).contains("member_no M2 now exists");
        assertThat(count("members", community.getId())).as("nothing applied").isOne();
        assertThat(jdbc.queryForObject("select status from import_batches where community_id = ?", String.class, community.getId())).isEqualTo("DRY_RUN");
    }

    @Test
    void anArchivedCommunityCannotBeImportedInto() {
        UUID community = newCommunity();
        jdbc.update("update communities set status = 'ARCHIVED' where id = ?", community);

        ApiClient.Response response = upload(community, UUID.randomUUID(), files(MEMBERS_HEADER + "M1,Asha,,,,,,\n", null, null));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("INVALID_STATE_TRANSITION");
    }

    @Test
    void unknownCommunitiesBatchesAndBadIdsAre404Or400() {
        assertThat(upload(UUID.randomUUID(), UUID.randomUUID(), files(MEMBERS_HEADER + "M1,Asha,,,,,,\n", null, null)).status()).isEqualTo(404);
        UUID community = newCommunity();
        assertThat(confirm(community, UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(adminGet(path(community) + "/" + UUID.randomUUID()).status()).isEqualTo(404);

        ApiClient.Response badBatch = api.postMultipart(path(community), Map.of("batchId", "not-a-uuid"), files(MEMBERS_HEADER + "M1,A,,,,,,\n", null, null), "Authorization", ApiClient.bearer(superToken));
        assertThat(badBatch.status()).isEqualTo(400);
        ApiClient.Response noBatch = api.postMultipart(path(community), Map.of(), files(MEMBERS_HEADER + "M1,A,,,,,,\n", null, null), "Authorization", ApiClient.bearer(superToken));
        assertThat(noBatch.status()).isEqualTo(400);
    }

    @Test
    void aBatchBelongsToItsCommunityAndIsNotVisibleFromAnother() {
        UUID one = newCommunity();
        UUID two = newCommunity();
        UUID batch = UUID.randomUUID();
        upload(one, batch, files(MEMBERS_HEADER + "M1,Asha,,,,,,\n", null, null));

        assertThat(adminGet(path(one) + "/" + batch).status()).isEqualTo(200);
        assertThat(adminGet(path(two) + "/" + batch).status()).isEqualTo(404);
        assertThat(confirm(two, batch, null).status()).isEqualTo(404);
        assertThat(count("members", two)).isZero();

        // The same batch id can be used independently by another community.
        assertThat(upload(two, batch, files(MEMBERS_HEADER + "M1,Other,,,,,,\n", null, null)).status()).isEqualTo(201);
    }

    @Test
    void getShowsTheReportAndResult() {
        UUID community = newCommunity();
        UUID batch = UUID.randomUUID();
        upload(community, batch, files(MEMBERS_HEADER + "M1,Asha,,,,,,\n", null, null));

        assertThat(adminGet(path(community) + "/" + batch).json().get("status").asString()).isEqualTo("DRY_RUN");
        confirm(community, batch, null);
        ApiClient.Response done = adminGet(path(community) + "/" + batch);
        assertThat(done.json().get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(done.json().get("result").get("membersCreated").asInt()).isOne();
    }

    @Test
    void importedPeopleAreNeverEmailedAndNothingIsSentOnImport() {
        UUID community = newCommunity();
        UUID batch = UUID.randomUUID();
        String email = "imp-" + uniq() + "@example.test";
        long before = jdbc.queryForObject("select count(*) from email_outbox", Long.class);
        upload(community, batch, files(MEMBERS_HEADER + "M1,Asha," + email + ",,,,,yes\n", null, null));
        confirm(community, batch, null);
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where to_email = ?", Long.class, email)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_outbox", Long.class)).isGreaterThanOrEqualTo(before);
    }
}
