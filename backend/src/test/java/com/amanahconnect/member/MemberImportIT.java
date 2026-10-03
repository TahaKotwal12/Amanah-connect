package com.amanahconnect.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.plan.Plan;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.tenant.AbstractTenantIT;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MemberImportIT extends AbstractTenantIT {

    private static final String IMPORT = "/api/v1/community/members/import";
    private static final String HEADER = "member_no,full_name,email,phone,group,status,joined_on,consent_email\n";
    private static final String NO_NUMBER_HEADER = "full_name,email,phone,group,status,joined_on,consent_email\n";

    private ApiClient.Response uploadAs(Session session, UUID batch, String csv) {
        return api.postMultipart(IMPORT, Map.of("batchId", batch.toString()), Map.of("file", csv.getBytes(StandardCharsets.UTF_8)), "Authorization", session.bearer());
    }

    private ApiClient.Response uploadA(UUID batch, String csv) {
        return uploadAs(sessionA, batch, csv);
    }

    private ApiClient.Response confirmA(UUID batch, Object body) {
        return asA("POST", IMPORT + "/" + batch + "/confirm", body);
    }

    private long members(Community c) {
        return jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, c.getId());
    }

    private String prefix() {
        return jdbc.queryForObject("select upper(substr(regexp_replace(slug, '[^a-z0-9]', '', 'g'), 1, 8)) from communities where id = ?", String.class, communityA.getId());
    }

    private void limitPlanA(int max) {
        Plan plan = data.customPlan("Limit " + max, Map.of("max_members", max), Map.of());
        jdbc.update("update communities set plan_id = ? where id = ?", plan.getId(), communityA.getId());
    }

    @Test
    void aDryRunReportsEveryRowAndWritesNothing() {
        String csv = NO_NUMBER_HEADER
                + "Asha Rao,asha@example.test,+91 98765 43210,Block A,ACTIVE,2020-04-01,yes\n"
                + ",nobody@example.test,,,,,\n"
                + "Bad Email,not-an-email,,,,,\n"
                + "Bad Status,,,,PAUSED,,\n"
                + "Future Joiner,,,,,2999-01-01,\n"
                + "Ravi Kumar,,,,INACTIVE,15/08/2021,no\n";

        ApiClient.Response response = uploadA(UUID.randomUUID(), csv);

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        JsonNode file = response.json().get("members");
        assertThat(response.json().get("status").asString()).isEqualTo("DRY_RUN");
        assertThat(response.json().get("confirmable").asBoolean()).isTrue();
        assertThat(file.get("totalRows").asInt()).isEqualTo(6);
        assertThat(file.get("validRows").asInt()).isEqualTo(2);
        assertThat(file.get("invalidRows").asInt()).isEqualTo(4);
        String invalid = file.get("invalid").toString();
        assertThat(invalid).contains("full_name is required").contains("email is not a valid address").contains("status must be ACTIVE or INACTIVE").contains("joined_on is in the future");
        assertThat(members(communityA)).as("a dry run changes nothing").isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_IMPORT_DRY_RUN' and community_id = ?", Long.class, communityA.getId())).isOne();
    }

    @Test
    void confirmCreatesTheMembersWithGeneratedNumbersAndSendsNoEmail() {
        String prefix = prefix();
        jdbc.update("insert into members (id, community_id, member_no, full_name) values (gen_random_uuid(), ?, ?, 'Existing')", communityA.getId(), prefix + "-0002");
        String csv = HEADER
                + ",Asha Rao,asha@example.test,+91 98765 43210,Block A,ACTIVE,2020-04-01,yes\n"
                + "CUSTOM-7,Ravi Kumar,ravi@example.test,,Block B,INACTIVE,,no\n"
                + ",Sunita Devi,,,,,,\n";
        UUID batch = UUID.randomUUID();
        uploadA(batch, csv);

        ApiClient.Response response = confirmA(batch, null);

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(response.json().get("result").get("membersCreated").asInt()).isEqualTo(3);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from members where community_id = ? and full_name <> 'Existing' order by created_at, member_no", communityA.getId());
        assertThat(rows).hasSize(3);
        Map<String, Map<String, Object>> byName = new java.util.HashMap<>();
        rows.forEach(r -> byName.put((String) r.get("full_name"), r));
        assertThat(byName.get("Asha Rao").get("member_no")).as("0001 is free; 0002 was taken").isEqualTo(prefix + "-0001");
        assertThat(byName.get("Sunita Devi").get("member_no")).isEqualTo(prefix + "-0003");
        assertThat(byName.get("Ravi Kumar").get("member_no")).as("an explicit number is kept").isEqualTo("CUSTOM-7");
        assertThat(byName.get("Ravi Kumar").get("status")).isEqualTo("INACTIVE");
        assertThat(byName.get("Asha Rao").get("consent_email")).isEqualTo(true);
        assertThat(byName.get("Asha Rao").get("joined_on").toString()).isEqualTo("2020-04-01");
        assertThat(byName.get("Asha Rao").get("group_label")).isEqualTo("Block A");
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where community_id = ?", Long.class, communityA.getId())).as("no welcome emails for imported members").isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_IMPORT_CONFIRMED' and community_id = ?", Long.class, communityA.getId())).isOne();
        assertThat(asA("GET", MemberIT.MEMBERS + "?q=Asha", null).json().get("total").asInt()).as("they show up in the member list").isOne();
    }

    @Test
    void sharedEmailsAreWarningsNotErrors() {
        jdbc.update("insert into members (id, community_id, member_no, full_name, email) values (gen_random_uuid(), ?, 'OLD-1', 'Existing', 'family@example.test')", communityA.getId());
        String csv = NO_NUMBER_HEADER
                + "Parent One,family@example.test,,,,,\n"
                + "Parent Two,FAMILY@example.test,,,,,\n"
                + "Other,other@example.test,,,,,\n"
                + "Other Twin,other@example.test,,,,,\n";

        ApiClient.Response response = uploadA(UUID.randomUUID(), csv);

        assertThat(response.json().get("members").get("validRows").asInt()).isEqualTo(4);
        String warnings = response.json().get("members").get("warnings").toString();
        assertThat(warnings).contains("already used by member OLD-1").contains("is also used on row");
        assertThat(response.json().get("confirmable").asBoolean()).isTrue();
    }

    @Test
    void confirmingTwiceAndRepeatingTheUploadAreIdempotent() {
        UUID batch = UUID.randomUUID();
        String csv = NO_NUMBER_HEADER + "Asha,,,,,,\nRavi,,,,,,\n";
        ApiClient.Response first = uploadA(batch, csv);

        ApiClient.Response replay = uploadA(batch, csv);
        ApiClient.Response different = uploadA(batch, csv + "Extra,,,,,,\n");
        confirmA(batch, null);
        ApiClient.Response again = confirmA(batch, Map.of("skipInvalidRows", true));
        ApiClient.Response afterConfirm = uploadA(batch, csv);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(different.status()).isEqualTo(409);
        assertThat(different.code()).isEqualTo("IMPORT_BATCH_CONFLICT");
        assertThat(again.status()).isEqualTo(200);
        assertThat(afterConfirm.json().get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(members(communityA)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'COMMUNITY_IMPORT_CONFIRMED' and community_id = ?", Long.class, communityA.getId())).isOne();
    }

    @Test
    void invalidRowsBlockTheConfirmUnlessSkipped() {
        UUID batch = UUID.randomUUID();
        uploadA(batch, NO_NUMBER_HEADER + "Good,,,,,,\n,bad@example.test,,,,,\n");

        ApiClient.Response refused = confirmA(batch, null);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("IMPORT_NOT_CONFIRMABLE");
        assertThat(members(communityA)).isZero();

        ApiClient.Response skipped = confirmA(batch, Map.of("skipInvalidRows", true));
        assertThat(skipped.status()).isEqualTo(200);
        assertThat(skipped.json().get("result").get("skippedInvalidRows").asInt()).isOne();
        assertThat(members(communityA)).isOne();
    }

    // ---- plan limit ---------------------------------------------------------------------------------------------------

    @Test
    void anImportBeyondThePlanLimitIsBlockedAndConfirmAnswers402() {
        limitPlanA(3);
        data.member(communityA);
        data.member(communityA);
        UUID batch = UUID.randomUUID();

        ApiClient.Response dry = uploadA(batch, NO_NUMBER_HEADER + "One,,,,,,\nTwo,,,,,,\n");

        assertThat(dry.json().get("confirmable").asBoolean()).isFalse();
        assertThat(dry.json().get("blockingErrors").toString()).contains("allows 3 members").contains("1 over the limit");
        ApiClient.Response confirm = confirmA(batch, null);
        assertThat(confirm.status()).isEqualTo(402);
        assertThat(confirm.code()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        assertThat(members(communityA)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from document_counters where community_id = ? and counter_type = 'MEMBER' and last_value > 0", Long.class, communityA.getId())).as("no numbers burned").isZero();
    }

    @Test
    void anImportThatExactlyFillsThePlanWorksAndTheLimitIsReCheckedAtConfirm() {
        limitPlanA(3);
        data.member(communityA);
        UUID fits = UUID.randomUUID();
        assertThat(uploadA(fits, NO_NUMBER_HEADER + "One,,,,,,\nTwo,,,,,,\n").json().get("confirmable").asBoolean()).isTrue();
        UUID late = UUID.randomUUID();
        uploadA(late, NO_NUMBER_HEADER + "Late,,,,,,\n");

        assertThat(confirmA(fits, null).status()).isEqualTo(200);
        assertThat(members(communityA)).isEqualTo(3);
        assertThat(confirmA(late, null).status()).as("the plan filled up between dry run and confirm").isEqualTo(402);
        assertThat(members(communityA)).isEqualTo(3);
    }

    @Test
    void theCommunityChangingBetweenDryRunAndConfirmIsDetected() {
        UUID batch = UUID.randomUUID();
        uploadA(batch, HEADER + "FIXED-1,Asha,,,,,,\n");
        jdbc.update("insert into members (id, community_id, member_no, full_name) values (gen_random_uuid(), ?, 'FIXED-1', 'Added meanwhile')", communityA.getId());

        ApiClient.Response response = confirmA(batch, null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("IMPORT_CONFLICT");
        assertThat(members(communityA)).isOne();
    }

    // ---- bad files -----------------------------------------------------------------------------------------------------

    @Test
    void refusesFilesThatAreNotCsvAndReportsMissingColumns() {
        byte[] xlsx = {'P', 'K', 3, 4, 20, 0, 0, 0};
        ApiClient.Response excel = api.postMultipart(IMPORT, Map.of("batchId", UUID.randomUUID().toString()), Map.of("file", xlsx), "Authorization", sessionA.bearer());
        assertThat(excel.status()).isEqualTo(400);
        assertThat(excel.json().toString()).contains("Excel");

        ApiClient.Response missing = uploadA(UUID.randomUUID(), "email,phone\na@example.test,123456\n");
        assertThat(missing.status()).isEqualTo(201);
        assertThat(missing.json().get("confirmable").asBoolean()).isFalse();
        assertThat(missing.json().get("blockingErrors").toString()).contains("Missing required column 'full_name'");

        ApiClient.Response noFile = api.postMultipart(IMPORT, Map.of("batchId", UUID.randomUUID().toString()), Map.of(), "Authorization", sessionA.bearer());
        assertThat(noFile.status()).isEqualTo(400);
        ApiClient.Response badBatch = api.postMultipart(IMPORT, Map.of("batchId", "nope"), Map.of("file", "a".getBytes()), "Authorization", sessionA.bearer());
        assertThat(badBatch.status()).isEqualTo(400);
        assertThat(members(communityA)).isZero();
    }

    @Test
    void aSuspendedCommunityCannotImport() {
        jdbc.update("update communities set status = 'SUSPENDED' where id = ?", communityA.getId());
        ApiClient.Response response = uploadA(UUID.randomUUID(), NO_NUMBER_HEADER + "One,,,,,,\n");
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("COMMUNITY_SUSPENDED");
    }

    // ---- tenant isolation ----------------------------------------------------------------------------------------------

    @Test
    void importBatchesBelongToTheirCommunity() {
        UUID batchB = UUID.randomUUID();
        assertThat(uploadAs(sessionB, batchB, NO_NUMBER_HEADER + "B Member,,,,,,\n").status()).isEqualTo(201);

        assertCrossTenantRead(IMPORT + "/" + batchB);
        assertCrossTenantUpdate("POST", IMPORT + "/" + batchB + "/confirm", Map.of(), () -> {
            assertThat(jdbc.queryForObject("select status from import_batches where batch_id = ?", String.class, batchB)).isEqualTo("DRY_RUN");
            assertThat(jdbc.queryForObject("select count(*) from members where community_id = ?", Long.class, communityA.getId())).isZero();
        });
        assertThat(members(communityB)).isOne();

        // A's upload lands in A even when it also names B's community, and B cannot see it.
        UUID batchA = UUID.randomUUID();
        ApiClient.Response forged = api.postMultipart(IMPORT + "?communityId=" + communityB.getId(), Map.of("batchId", batchA.toString(), "communityId", communityB.getId().toString()),
                Map.of("file", (NO_NUMBER_HEADER + "A Member,,,,,,\n").getBytes(StandardCharsets.UTF_8)), "Authorization", sessionA.bearer(), "X-Community-Id", communityB.getId().toString());
        assertThat(forged.status()).as(forged.body()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select community_id from import_batches where batch_id = ?", UUID.class, batchA)).isEqualTo(communityA.getId());
        assertThat(asB("GET", IMPORT + "/" + batchA, null).status()).isEqualTo(404);
        markCovered("POST", IMPORT);

        // The same batch id can be used by both communities independently.
        assertThat(uploadAs(sessionB, batchA, NO_NUMBER_HEADER + "Another,,,,,,\n").status()).isEqualTo(201);
    }
}
