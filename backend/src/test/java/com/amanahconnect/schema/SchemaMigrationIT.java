package com.amanahconnect.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.community.Community;
import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.support.TestData;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Boots real Postgres, runs every migration, and checks the schema, the seed data and the mapping. */
@Transactional
class SchemaMigrationIT extends AbstractIntegrationTest {

    static final Set<String> EXPECTED_TABLES =
            Set.of(
                    "users", "refresh_tokens", "recovery_codes", "auth_tokens",
                    "plans", "communities", "community_users", "platform_subscriptions",
                    "members", "member_invites", "member_registrations",
                    "document_counters", "fee_plans", "invoices", "payment_records", "receipts",
                    "ledger_category_templates", "ledger_categories", "ledger_entries",
                    "complaints", "complaint_comments", "support_threads", "support_messages", "announcements",
                    "email_outbox", "notification_settings", "audit_logs", "leads",
                    "shedlock");

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired EntityManager em;
    @Autowired TestData data;

    @Test
    void everyMigrationRanSuccessfully() {
        List<String> versions =
                jdbc.queryForList(
                        "select version from flyway_schema_history where success order by installed_rank",
                        String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }

    @Test
    void createsExactlyTheExpectedTables() {
        List<String> tables =
                jdbc.queryForList(
                        "select table_name from information_schema.tables "
                                + "where table_schema = 'public' and table_type = 'BASE TABLE' "
                                + "and table_name <> 'flyway_schema_history'",
                        String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    void everyTableExceptShedlockHasAnEntity() {
        Set<String> entityTables = new TreeSet<>();
        for (EntityType<?> type : emf.getMetamodel().getEntities()) {
            entityTables.add(type.getJavaType().getAnnotation(Table.class).name());
        }

        Set<String> expected = new TreeSet<>(EXPECTED_TABLES);
        expected.remove("shedlock"); // library-owned table, accessed through ShedLock, not JPA
        assertThat(entityTables).isEqualTo(expected);
    }

    @Test
    void hibernateRunsInValidateMode() {
        // The context only started because Hibernate validated every entity against the migrated schema.
        assertThat(emf.getProperties().get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
    }

    @Test
    void seedsThreeDefaultPlansWithLimitsAndFeatures() {
        List<String> codes =
                jdbc.queryForList("select code from plans order by sort_order", String.class);
        assertThat(codes).containsExactly("STARTER", "GROWTH", "ENTERPRISE");

        assertThat(
                        jdbc.queryForObject(
                                "select limits->>'max_members' from plans where code = 'STARTER'",
                                String.class))
                .isEqualTo("100");
        assertThat(
                        jdbc.queryForObject(
                                "select limits->'max_members' = 'null'::jsonb from plans where code = 'ENTERPRISE'",
                                Boolean.class))
                .as("enterprise member limit is unlimited (JSON null)")
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select features->>'pdf_reports' from plans where code = 'STARTER'",
                                String.class))
                .isEqualTo("false");
        assertThat(
                        jdbc.queryForObject(
                                "select features->>'pdf_reports' from plans where code = 'GROWTH'",
                                String.class))
                .isEqualTo("true");
    }

    @Test
    void seedsNoUsersAtAll() {
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from community_users", Long.class)).isZero();
    }

    @Test
    void seedsTheDefaultLedgerCategoryTemplates() {
        List<String> names =
                jdbc.queryForList(
                        "select distinct name from ledger_category_templates", String.class);

        assertThat(names)
                .contains("Maintenance", "Utilities", "Repairs", "Events", "Donations", "Administration", "Other");
    }

    @Test
    void newCommunityGetsItsOwnCopyOfTheCategoriesAndNotificationSettings() {
        Community a = data.community();
        Community b = data.community();

        Long templates = jdbc.queryForObject("select count(*) from ledger_category_templates", Long.class);
        for (Community community : List.of(a, b)) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from ledger_categories where community_id = ?",
                                    Long.class,
                                    community.getId()))
                    .isEqualTo(templates);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from notification_settings where community_id = ?",
                                    Long.class,
                                    community.getId()))
                    .isEqualTo(1L);
        }
        List<java.util.UUID> idsA =
                jdbc.queryForList("select id from ledger_categories where community_id = ?", java.util.UUID.class, a.getId());
        List<java.util.UUID> idsB =
                jdbc.queryForList("select id from ledger_categories where community_id = ?", java.util.UUID.class, b.getId());
        assertThat(idsA).as("categories are copied, not shared").doesNotContainAnyElementsOf(idsB);
    }

    @Test
    void everyEnumFieldMatchesItsCheckConstraint() {
        List<String> problems = new ArrayList<>();
        Pattern literal = Pattern.compile("'([A-Z_]+)'");
        int checked = 0;
        for (EntityType<?> type : emf.getMetamodel().getEntities()) {
            Class<?> entity = type.getJavaType();
            String table = entity.getAnnotation(Table.class).name();
            for (Field field : entity.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Enumerated.class)) {
                    continue;
                }
                String column = field.getAnnotation(Column.class).name();
                String constraint = "ck_" + table + "_" + column;
                List<String> defs =
                        jdbc.queryForList(
                                "select pg_get_constraintdef(oid) from pg_constraint where conname = ?",
                                String.class,
                                constraint);
                if (defs.size() != 1) {
                    problems.add(constraint + ": expected one CHECK constraint, found " + defs.size());
                    continue;
                }
                Set<String> dbValues = new TreeSet<>();
                Matcher matcher = literal.matcher(defs.get(0));
                while (matcher.find()) {
                    dbValues.add(matcher.group(1));
                }
                Set<String> javaValues = new TreeSet<>();
                for (Object constant : field.getType().getEnumConstants()) {
                    javaValues.add(((Enum<?>) constant).name());
                }
                if (!dbValues.equals(javaValues)) {
                    problems.add(constraint + ": DB " + dbValues + " != Java " + javaValues);
                }
                checked++;
            }
        }

        assertThat(problems).isEmpty();
        assertThat(checked).as("enum fields checked").isGreaterThan(20);
    }

    @Test
    void everyCommunityIdColumnIsTheLeadingColumnOfAnIndex() {
        List<String> unindexed =
                jdbc.queryForList(
                        """
                        select c.table_name
                        from information_schema.columns c
                        where c.table_schema = 'public' and c.column_name = 'community_id'
                          and not exists (
                              select 1
                              from pg_index i
                              join pg_class t on t.oid = i.indrelid
                              join pg_attribute a on a.attrelid = t.oid and a.attnum = i.indkey[0]
                              where t.relname = c.table_name and a.attname = 'community_id')
                        """,
                        String.class);

        assertThat(unindexed).isEmpty();
    }

    @Test
    void everyTokenHashAndEmailColumnIsIndexed() {
        List<String> unindexed =
                jdbc.queryForList(
                        """
                        select c.table_name || '.' || c.column_name
                        from information_schema.columns c
                        where c.table_schema = 'public'
                          and (c.column_name in ('token_hash', 'email', 'contact_email', 'to_email'))
                          and not exists (
                              select 1
                              from pg_index i
                              join pg_class t on t.oid = i.indrelid
                              join pg_attribute a on a.attrelid = t.oid
                              where t.relname = c.table_name and a.attname = c.column_name
                                and a.attnum = any (i.indkey::int2[]))
                        """,
                        String.class);

        assertThat(unindexed).isEmpty();
    }

    @Test
    void moneyColumnsAreNumeric14And2() {
        List<String> wrong =
                jdbc.queryForList(
                        """
                        select table_name || '.' || column_name || ' ' || data_type
                               || '(' || coalesce(numeric_precision::text, '') || ',' || coalesce(numeric_scale::text, '') || ')'
                        from information_schema.columns
                        where table_schema = 'public'
                          and (column_name in ('amount', 'amount_paid', 'price_monthly', 'price_yearly'))
                          and not (data_type = 'numeric' and numeric_precision = 14 and numeric_scale = 2)
                        """,
                        String.class);

        assertThat(wrong).isEmpty();
    }

    @Test
    void timestampsAreTimestamptz() {
        List<String> wrong =
                jdbc.queryForList(
                        """
                        select table_name || '.' || column_name
                        from information_schema.columns
                        where table_schema = 'public' and table_name <> 'shedlock'
                          and (column_name like '%\\_at' or column_name = 'next_attempt_at')
                          and data_type <> 'timestamp with time zone'
                        """,
                        String.class);

        assertThat(wrong).isEmpty();
    }
}
