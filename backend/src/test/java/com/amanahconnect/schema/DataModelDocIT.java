package com.amanahconnect.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * docs/data-model.md is generated from the real schema (after all Flyway migrations), so it cannot drift. The test compares the committed
 * file with what the database says today and fails with the command that regenerates it:
 * {@code ./mvnw verify -Dit.test=DataModelDocIT -DargLine="-Dgenerate.docs=true"}.
 * Every table must belong to a domain below, so a new table cannot be forgotten.
 */
class DataModelDocIT extends AbstractIntegrationTest {

    private static final Map<String, List<String>> DOMAINS = new LinkedHashMap<>();
    private static final Set<String> NOT_DOCUMENTED = Set.of("flyway_schema_history", "shedlock");

    static {
        DOMAINS.put("Identity, plans and platform", List.of("users", "refresh_tokens", "recovery_codes", "auth_tokens", "plans", "communities", "community_users", "platform_subscriptions", "leads", "audit_logs", "import_batches", "data_exports"));
        DOMAINS.put("Members", List.of("members", "member_invites", "member_registrations", "document_counters"));
        DOMAINS.put("Billing and ledger", List.of("fee_plans", "invoices", "payment_records", "receipts", "payment_links", "invoice_reminders", "ledger_category_templates", "ledger_categories", "ledger_entries"));
        DOMAINS.put("Communication and files", List.of("complaints", "complaint_comments", "support_threads", "support_messages", "announcements", "announcement_reads", "email_outbox", "email_suppressions", "notification_settings", "stored_files"));
    }

    @Autowired JdbcTemplate jdbc;

    private record Column(String name, String type, boolean nullable, boolean pk, boolean unique) {}

    private record Fk(String child, String parent, String columns) {}

    @Test
    void theCommittedDataModelMatchesTheMigratedSchema() throws IOException {
        String generated = generate();
        Path file = Path.of("..", "docs", "data-model.md");
        if (Boolean.getBoolean("generate.docs")) {
            Files.writeString(file, generated);
        }
        assertThat(file).as("run: ./mvnw verify -Dit.test=DataModelDocIT -DargLine=\"-Dgenerate.docs=true\"").exists();
        assertThat(Files.readString(file)).as("docs/data-model.md is stale; regenerate it with -Dgenerate.docs=true").isEqualTo(generated);
    }

    @Test
    void everyTableBelongsToADomain() {
        Set<String> tables = new TreeSet<>(jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'", String.class));
        tables.removeAll(NOT_DOCUMENTED);
        Set<String> mapped = new TreeSet<>();
        DOMAINS.values().forEach(mapped::addAll);
        assertThat(tables).as("a new table must be added to a domain in DataModelDocIT").isEqualTo(mapped);
    }

    private String generate() {
        Map<String, List<Column>> columns = new TreeMap<>();
        Set<String> pk = new LinkedHashSet<>();
        Set<String> unique = new LinkedHashSet<>();
        jdbc.query("""
                select tc.table_name, kcu.column_name, tc.constraint_type
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu on kcu.constraint_name = tc.constraint_name and kcu.table_schema = tc.table_schema
                where tc.table_schema = 'public' and tc.constraint_type in ('PRIMARY KEY', 'UNIQUE')
                  and (select count(*) from information_schema.key_column_usage k2 where k2.constraint_name = tc.constraint_name and k2.table_schema = tc.table_schema) = 1
                """, rs -> {
            String key = rs.getString(1) + "." + rs.getString(2);
            if ("PRIMARY KEY".equals(rs.getString(3))) pk.add(key);
            else unique.add(key);
        });
        jdbc.query("select table_name, column_name, udt_name, is_nullable from information_schema.columns where table_schema = 'public' order by table_name, ordinal_position", rs -> {
            String table = rs.getString(1);
            String key = table + "." + rs.getString(2);
            columns.computeIfAbsent(table, t -> new ArrayList<>()).add(new Column(rs.getString(2), rs.getString(3), "YES".equals(rs.getString(4)), pk.contains(key), unique.contains(key)));
        });
        List<Fk> fks = new ArrayList<>();
        jdbc.query("""
                select c.conrelid::regclass::text as child, c.confrelid::regclass::text as parent,
                       (select string_agg(a.attname, ', ' order by k.ord) from unnest(c.conkey) with ordinality k(attnum, ord) join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum) as cols
                from pg_constraint c where c.contype = 'f' and c.connamespace = 'public'::regnamespace
                order by 1, 2, 3
                """, rs -> {
            fks.add(new Fk(rs.getString(1), rs.getString(2), rs.getString(3)));
        });
        Set<String> fkColumns = new LinkedHashSet<>();
        for (Fk fk : fks) for (String c : fk.columns().split(", ")) fkColumns.add(fk.child() + "." + c);

        StringBuilder md = new StringBuilder();
        md.append("# Data model\n\n");
        md.append("Generated from the migrated PostgreSQL schema by `DataModelDocIT`; do not edit by hand. To regenerate after a migration:\n\n");
        md.append("```\n./mvnw verify -Dit.test=DataModelDocIT -DargLine=\"-Dgenerate.docs=true\"\n```\n\n");
        md.append("Every tenant table carries `community_id`; composite foreign keys `(id, community_id)` make it impossible for a row to point at another community's row. ");
        md.append("Money is `numeric(14,2)`. Financial rows are never deleted (reversal rows instead). `audit_logs` is append-only (database triggers).\n\n");
        for (Map.Entry<String, List<String>> domain : DOMAINS.entrySet()) {
            Set<String> own = new TreeSet<>(domain.getValue());
            md.append("## ").append(domain.getKey()).append("\n\n```mermaid\nerDiagram\n");
            Set<String> stubs = new TreeSet<>();
            Set<String> lines = new LinkedHashSet<>();
            for (Fk fk : fks) {
                if (!own.contains(fk.child())) continue;
                if (fk.child().equals(fk.parent())) {
                    lines.add("    " + fk.parent() + " ||--o{ " + fk.child() + " : \"" + fk.columns() + "\"");
                    continue;
                }
                if (!own.contains(fk.parent())) stubs.add(fk.parent());
                lines.add("    " + fk.parent() + " ||--o{ " + fk.child() + " : \"" + fk.columns() + "\"");
            }
            lines.forEach(l -> md.append(l).append('\n'));
            for (String table : own) {
                md.append("    ").append(table).append(" {\n");
                for (Column c : columns.get(table)) {
                    String key = c.pk() ? " PK" : fkColumns.contains(table + "." + c.name()) ? " FK" : c.unique() ? " UK" : "";
                    md.append("        ").append(c.type()).append(' ').append(c.name()).append(key).append('\n');
                }
                md.append("    }\n");
            }
            for (String stub : stubs) md.append("    ").append(stub).append(" {\n        uuid id PK\n    }\n");
            md.append("```\n\n");
        }
        md.append("## Indexes and constraints\n\nSee the migrations in `backend/src/main/resources/db/migration`; they are the source of truth. ");
        md.append("`SchemaMigrationIT` and `ConstraintsIT` check that the tables exist and that the constraints reject bad data.\n");
        return md.toString();
    }
}
