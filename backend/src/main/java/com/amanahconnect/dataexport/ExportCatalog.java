package com.amanahconnect.dataexport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a data export contains, decided from the database itself: every table that has a {@code community_id} column, plus the community's own
 * row and its admins' names and addresses. A table added later is exported without anyone remembering to add it; what is deliberately left
 * out is listed here (secrets and password material), and a test checks that nothing else is missing.
 */
@Component
public class ExportCatalog {

    /** Columns that are secrets or only meaningful to the server, never exported. */
    static final Set<String> EXCLUDED_COLUMNS = Set.of("token_hash", "request_hash", "password_hash");

    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");
    private static final Set<String> TEXT_TYPES = Set.of("text", "character varying", "character", "citext", "json", "jsonb", "ARRAY", "USER-DEFINED");

    /** One column: its name and the SQL that renders it as plain text (timestamps as UTC ISO-8601). */
    public record Column(String name, String selectSql, boolean textLike) {}

    /** One CSV file: where its rows come from. */
    public record Source(String fileName, String fromWhere, List<Column> columns, String orderBy) {}

    private final NamedParameterJdbcTemplate jdbc;

    public ExportCatalog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The files of one community's export, in a stable order. {@code fromWhere} uses the named parameter {@code :c} (the community id). */
    public List<Source> sources() {
        List<Source> out = new ArrayList<>();
        Map<String, List<Column>> tenantTables = columnsOf("SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'community_id'");
        for (Map.Entry<String, List<Column>> t : tenantTables.entrySet()) {
            String table = t.getKey();
            out.add(new Source(table + ".csv", "FROM \"" + table + "\" WHERE community_id = :c", t.getValue(), orderOf(t.getValue())));
        }
        Map<String, List<Column>> communities = columnsOf("SELECT 'communities'");
        out.add(new Source("communities.csv", "FROM communities WHERE id = :c", communities.get("communities"), "\"id\""));
        out.add(new Source("community_admins.csv",
                "FROM community_users cu JOIN users u ON u.id = cu.user_id WHERE cu.community_id = :c",
                List.of(new Column("user_id", "u.id::text", false), new Column("full_name", "u.full_name::text", true), new Column("email", "u.email::text", true),
                        new Column("role", "u.role::text", true), new Column("status", "u.status::text", true), new Column("community_role", "cu.role::text", true),
                        new Column("added_at", "to_char(cu.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')", false)),
                "u.email, u.id"));
        out.sort((a, b) -> a.fileName().compareTo(b.fileName()));
        return out;
    }

    /** Every tenant table (with a community_id) that is exported, for the test that guards against forgetting one. */
    public Set<String> exportedTenantTables() {
        return sources().stream().map(s -> s.fileName().replace(".csv", "")).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    private Map<String, List<Column>> columnsOf(String tableListSql) {
        boolean communitiesOnly = tableListSql.startsWith("SELECT 'communities'");
        String tables = communitiesOnly ? "('communities')" : "(" + tableListSql + ")";
        List<Object[]> rows = jdbc.query(
                "SELECT c.table_name, c.column_name, c.data_type FROM information_schema.columns c"
                        + " JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'"
                        + " WHERE c.table_schema = 'public' AND c.table_name IN " + tables + " ORDER BY c.table_name, c.ordinal_position",
                new MapSqlParameterSource(), (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3)});
        Map<String, List<Column>> byTable = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String table = (String) r[0];
            String column = (String) r[1];
            String type = (String) r[2];
            if (!SAFE_NAME.matcher(table).matches() || !SAFE_NAME.matcher(column).matches() || EXCLUDED_COLUMNS.contains(column)) continue;
            String sql = type.equals("timestamp with time zone")
                    ? "to_char(\"" + column + "\" AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"
                    : "\"" + column + "\"::text";
            byTable.computeIfAbsent(table, k -> new ArrayList<>()).add(new Column(column, sql, TEXT_TYPES.contains(type)));
        }
        return byTable;
    }

    private static String orderOf(List<Column> columns) {
        for (Column c : columns) if (c.name().equals("id")) return "\"id\"";
        return "\"" + columns.get(0).name() + "\"";
    }
}
