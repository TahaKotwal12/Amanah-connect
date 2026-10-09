package com.amanahconnect.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.dashboard.DashboardService;
import com.amanahconnect.support.AbstractAuthIT;
import com.amanahconnect.support.ApiClient;
import com.amanahconnect.support.AuthTestUsers.TestUser;
import com.amanahconnect.support.TestData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Opt-in (run with {@code -Dperf=true -Dit.test=PerformanceIT}): seeds 50 communities, 20,000 members, 200,000 invoices, ~140,000 payments,
 * 50,000 ledger entries and 200,000 audit rows, then (1) captures the SQL the main list and aggregate endpoints run and EXPLAINs each
 * (a generic plan, so bound values do not matter), failing on a sequential scan of a big table, and (2) times the endpoints and checks
 * p95 against the 300 ms target. The report goes to target/perf/report.md. It is not part of the normal build: it takes about a minute and
 * fills the test database.
 */
@EnabledIfSystemProperty(named = "perf", matches = "true")
class PerformanceIT extends AbstractAuthIT {

    static final long P95_TARGET_MS = 300;
    static final Set<String> BIG = Set.of("members", "invoices", "payment_records", "ledger_entries", "audit_logs", "receipts", "email_outbox", "complaints");

    @Autowired TestData data;
    @Autowired DashboardService dashboards;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.testcontainers.postgresql.PostgreSQLContainer postgres;

    private final List<UUID> communityIds = new ArrayList<>();
    private final List<Community> communities = new ArrayList<>();

    private void seed() {
        for (int i = 0; i < 50; i++) communities.add(data.community());
        communities.forEach(c -> communityIds.add(c.getId()));
        UUID actor = users.superAdmin().id();
        String[] idStrings = communityIds.stream().map(UUID::toString).toArray(String[]::new);
        long t0 = System.currentTimeMillis();
        jdbc.update("insert into members (community_id, member_no, full_name, email, status, joined_on, consent_email) "
                + "select c.id, 'M' || lpad(g::text, 5, '0'), 'Member ' || g || ' ' || substr(md5(c.id::text || g), 1, 6), 'm' || g || '.' || substr(c.id::text, 1, 6) || '@example.test', "
                + "case when g % 20 = 0 then 'INACTIVE' else 'ACTIVE' end, current_date - (g % 700), g % 3 = 0 "
                + "from communities c cross join generate_series(1, 400) g where c.id::text = any(?)", (Object) idStrings);
        jdbc.update("insert into invoices (community_id, member_id, invoice_no, kind, period, amount, amount_paid, due_date, status, issued_on, cancel_reason) "
                + "select m.community_id, m.id, 'INV/' || substr(m.member_no, 2) || '/' || n, 'MAINTENANCE', 'P' || n, 500 + (n % 5) * 100, "
                + "case when n <= 6 then 500 + (n % 5) * 100 when n = 7 then 250 else 0 end, "
                + "current_date - ((10 - n) * 30) + 5, "
                + "case when n <= 6 then 'PAID' when n = 7 then 'PARTIAL' when n = 8 then 'OVERDUE' when n = 9 then 'ISSUED' else 'CANCELLED' end, "
                + "current_date - ((10 - n) * 30) - 5, case when n = 10 then 'duplicate' end "
                + "from members m cross join generate_series(1, 10) n where m.community_id::text = any(?)", (Object) idStrings);
        jdbc.update("insert into payment_records (community_id, invoice_id, member_id, amount, method, reference, received_on, recorded_by) "
                + "select i.community_id, i.id, i.member_id, i.amount_paid, 'UPI', 'UTR' || substr(md5(i.id::text), 1, 10), i.due_date, ? "
                + "from invoices i where i.amount_paid > 0 and i.community_id::text = any(?)", actor, idStrings);
        jdbc.update("insert into ledger_entries (community_id, type, category_id, amount, entry_date, title, created_by) "
                + "select c.id, 'INCOME', (select id from ledger_categories lc where lc.community_id = c.id and lc.type = 'INCOME' order by name limit 1), 100 + g % 900, current_date - (g % 365), 'Entry ' || g, ? "
                + "from communities c cross join generate_series(1, 1000) g where c.id::text = any(?)", actor, idStrings);
        jdbc.update("insert into audit_logs (actor_user_id, community_id, action, entity_type, entity_id, after, created_at) "
                + "select ?, c.id, (array['MEMBER_CREATED','INVOICE_CREATED','PAYMENT_RECORDED','LEDGER_ENTRY_CREATED','COMPLAINT_CREATED'])[1 + g % 5], 'Member', gen_random_uuid(), '{\"n\": 1}'::jsonb, now() - (g || ' minutes')::interval "
                + "from communities c cross join generate_series(1, 4000) g where c.id::text = any(?)", actor, idStrings);
        jdbc.update("insert into complaints (community_id, member_id, subject, description, status, priority, created_by, created_at, resolved_at) "
                + "select community_id, id, 'Complaint ' || n, 'Something is wrong', st, 'MEDIUM', ?, now() - interval '30 days', case when st = 'RESOLVED' then now() - interval '10 days' end from (select m.community_id, m.id, substr(m.member_no, 2) as n, (array['OPEN','IN_PROGRESS','RESOLVED'])[1 + (substr(m.member_no, 2)::int % 3)] as st from members m where m.community_id::text = any(?) and substr(m.member_no, 2)::int % 2 = 0) x "
               
                , actor, idStrings);
        jdbc.update("insert into receipts (community_id, receipt_no, payment_record_id) select community_id, 'RCP/' || row_number() over (partition by community_id order by id), id from payment_records where community_id::text = any(?)", (Object) idStrings);
        jdbc.update("update payment_records p set receipt_id = r.id from receipts r where r.payment_record_id = p.id and p.community_id::text = any(?)", (Object) idStrings);
        jdbc.update("insert into payment_records (community_id, invoice_id, member_id, amount, method, received_on, recorded_by, reversed_of, reversal_reason) "
                + "select community_id, invoice_id, member_id, -amount, method, received_on, recorded_by, id, 'seeded reversal' from payment_records where reversed_of is null and abs(hashtext(id::text)) % 100 = 0 and community_id::text = any(?)", (Object) idStrings);
        jdbc.execute("analyze");
        System.out.println("PERF seeded in " + (System.currentTimeMillis() - t0) + " ms");
        assertThat(jdbc.queryForObject("select count(*) from invoices where community_id::text = any(?)", Long.class, (Object) idStrings)).isEqualTo(200_000L);
    }

    private record Endpoint(String name, String path) {}

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("dashboard (cold)", "/api/v1/community/dashboard"),
            new Endpoint("members list", "/api/v1/community/members?size=20"),
            new Endpoint("members search", "/api/v1/community/members?q=Member%2012&size=20"),
            new Endpoint("members counts", "/api/v1/community/members/counts"),
            new Endpoint("invoices list", "/api/v1/community/invoices?size=20"),
            new Endpoint("invoices overdue", "/api/v1/community/invoices?status=OVERDUE&size=20"),
            new Endpoint("invoices outstanding", "/api/v1/community/invoices?outstandingOnly=true&size=20"),
            new Endpoint("payments list", "/api/v1/community/payments?size=20"),
            new Endpoint("ledger entries", "/api/v1/community/ledger/entries?size=20"),
            new Endpoint("audit trail", "/api/v1/community/audit?limit=50"),
            new Endpoint("audit trail, filtered", "/api/v1/community/audit?actionPrefix=PAYMENT_&limit=50"),
            new Endpoint("complaints list", "/api/v1/community/complaints?size=20"));

    @Test
    void indexesServeTheMainQueriesAndListsMeetTheTarget() throws Exception {
        seed();
        UUID target = communityIds.get(0);
        TestUser admin = users.extraAdminOf(communities.get(0));
        Session session = loginOk(admin);
        ApiClient.Response warm = api.call("GET", "/api/v1/community/members?size=20", null, "Authorization", session.bearer());
        assertThat(warm.status()).isEqualTo(200);

        StringBuilder report = new StringBuilder("# Performance report\n\nSeed: 50 communities, 20,000 members, 200,000 invoices, ~140,000 payments, 50,000 ledger entries, 200,000 audit rows (PostgreSQL 16, one machine, test JVM in-process).\n\n");
        List<String> seqScans = new ArrayList<>();
        StringBuilder flagged = new StringBuilder();

        // ---- 1. the real plan of every statement behind each endpoint ----
        enableAutoExplain();
        report.append("## Query plans (auto_explain, real parameters, 20,000 members / 200,000 invoices)\n\n| Endpoint | Statements | Big-table seq scans | Slowest statement (ms) |\n|---|---|---|---|\n");
        for (Endpoint e : ENDPOINTS) {
            dashboards.evictAll();
            List<Plan> plans = plansOf(() -> api.call("GET", e.path(), null, "Authorization", session.bearer()));
            assertThat(plans).as("auto_explain captured the statements of " + e.name()).isNotEmpty();
            List<String> scans = new ArrayList<>();
            double slowest = 0;
            for (Plan plan : plans) {
                slowest = Math.max(slowest, plan.ms());
                List<String> rels = bigSeqScans(plan.text());
                for (String rel : rels) scans.add(rel + " in [" + plan.sql().substring(0, Math.min(90, plan.sql().length())) + "]");
                if (!rels.isEmpty()) flagged.append("\n### ").append(e.name()).append("\n\n```\n").append(plan.text().strip()).append("\n```\n");
            }
            scans.forEach(sc -> seqScans.add(e.name() + ": " + sc));
            report.append("| ").append(e.name()).append(" | ").append(plans.size()).append(" | ").append(scans.isEmpty() ? "none" : scans.size() + " (see below)")
                    .append(" | ").append(String.format("%.1f", slowest)).append(" |\n");
        }
        if (!seqScans.isEmpty()) report.append("\nSequential scans of big tables:\n\n").append(seqScans.stream().map(x -> "* " + x).collect(java.util.stream.Collectors.joining("\n"))).append("\n");

        report.append(flagged);

        // ---- 2. timings ----
        report.append("\n## Latency (25 sequential requests each, after warm-up)\n\nTarget: p95 < ").append(P95_TARGET_MS).append(" ms for list endpoints.\n\n| Endpoint | p50 ms | p95 ms | max ms |\n|---|---|---|---|\n");
        Map<String, Long> p95 = new LinkedHashMap<>();
        for (Endpoint e : ENDPOINTS) {
            List<Long> ms = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                if (e.name().startsWith("dashboard")) dashboards.evictAll();
                long t = System.nanoTime();
                ApiClient.Response r = api.call("GET", e.path(), null, "Authorization", session.bearer());
                ms.add((System.nanoTime() - t) / 1_000_000);
                assertThat(r.status()).as(e.name() + " " + r.body()).isEqualTo(200);
            }
            ms.sort(Long::compare);
            long p = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
            p95.put(e.name(), p);
            report.append("| ").append(e.name()).append(" | ").append(ms.get(ms.size() / 2)).append(" | ").append(p).append(" | ").append(ms.get(ms.size() - 1)).append(" |\n");
        }
        List<Long> logins = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long t = System.nanoTime();
            login(admin);
            logins.add((System.nanoTime() - t) / 1_000_000);
        }
        logins.sort(Long::compare);
        report.append("\nLogin (BCrypt strength 4 in tests, so production is slower by design: about 250 ms at strength 12): median ").append(logins.get(5)).append(" ms.\n");

        Path out = Path.of("target/perf/report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
        System.out.println(report);

        assertThat(seqScans).as("sequential scans of big tables in main query plans").isEmpty();
        p95.forEach((name, ms) -> assertThat(ms).as("p95 of " + name).isLessThan(P95_TARGET_MS));
    }

    // ---- helpers: auto_explain gives the real plan of every statement, with the real parameter values ----

    private void enableAutoExplain() {
        String db = jdbc.queryForObject("select current_database()", String.class);
        jdbc.execute("alter database " + db + " set session_preload_libraries = 'auto_explain'");
        jdbc.execute("alter database " + db + " set auto_explain.log_min_duration = 0");
        jdbc.execute("alter database " + db + " set auto_explain.log_analyze = on");
        jdbc.execute("alter database " + db + " set auto_explain.log_nested_statements = on");
        ((com.zaxxer.hikari.HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections(); // new connections pick the setting up
    }

    private record Plan(String sql, double ms, String text) {}

    private List<Plan> plansOf(Runnable call) throws InterruptedException {
        int before = postgres.getLogs().length();
        call.run();
        Thread.sleep(300);
        String logs = postgres.getLogs().substring(Math.min(before, postgres.getLogs().length()));
        List<Plan> out = new ArrayList<>();
        Matcher m = Pattern.compile("duration: ([0-9.]+) ms\\s+plan:\\s*\\n?(.*?)(?=\\n\\d{4}-\\d\\d-\\d\\d |\\z)", Pattern.DOTALL).matcher(logs);
        while (m.find()) {
            String text = m.group(2);
            Matcher q = Pattern.compile("Query Text: (.*?)\\n\\s*\\S", Pattern.DOTALL).matcher(text);
            String sql = q.find() ? q.group(1).replaceAll("\\s+", " ") : "?";
            out.add(new Plan(sql, Double.parseDouble(m.group(1)), text));
        }
        return out;
    }

    private static List<String> bigSeqScans(String planText) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("Seq Scan on (\\w+)").matcher(planText);
        while (m.find()) if (BIG.contains(m.group(1))) out.add(m.group(1));
        return out;
    }
}
