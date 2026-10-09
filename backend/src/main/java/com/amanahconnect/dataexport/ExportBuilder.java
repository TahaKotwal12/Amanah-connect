package com.amanahconnect.dataexport;

import com.amanahconnect.common.csv.CsvWriter;
import com.amanahconnect.dataexport.ExportCatalog.Column;
import com.amanahconnect.dataexport.ExportCatalog.Source;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one community's data as a ZIP of CSV files (one per table, with a header row, UTF-8, UTC timestamps, plain decimal amounts) plus a README
 * and a manifest. Rows are read through a database cursor and written as they arrive, straight to a temporary file, so memory does not depend on
 * the size of the community. Run it inside one read-only REPEATABLE READ transaction so all the files describe the same moment.
 */
@Component
public class ExportBuilder {

    public record Built(Path file, long sizeBytes, Map<String, Long> rows) {}

    private final ExportCatalog catalog;
    private final NamedParameterJdbcTemplate cursor;
    private final JsonMapper json;

    public ExportBuilder(ExportCatalog catalog, DataSource dataSource, JsonMapper json) {
        this.catalog = catalog;
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(1000);
        this.cursor = new NamedParameterJdbcTemplate(template);
        this.json = json;
    }

    public Built build(UUID communityId, String communityName, Instant at, Path target) throws IOException {
        Map<String, Long> rows = new LinkedHashMap<>();
        try (OutputStream file = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            for (Source source : catalog.sources()) {
                zip.putNextEntry(new ZipEntry(source.fileName()));
                long[] count = {0};
                write(zip, new CsvWriter().row(source.columns().stream().map(Column::name).toList()).toString());
                String select = "SELECT " + String.join(", ", source.columns().stream().map(Column::selectSql).toList()) + " " + source.fromWhere() + " ORDER BY " + source.orderBy();
                cursor.query(select, new MapSqlParameterSource("c", communityId), rs -> {
                    StringBuilder line = new StringBuilder();
                    List<Column> columns = source.columns();
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) line.append(',');
                        line.append(CsvWriter.cell(rs.getString(i + 1), !columns.get(i).textLike()));
                    }
                    line.append("\r\n");
                    write(zip, line.toString());
                    count[0]++;
                });
                zip.closeEntry();
                rows.put(source.fileName().replace(".csv", ""), count[0]);
            }
            zip.putNextEntry(new ZipEntry("manifest.json"));
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("community", communityName);
            manifest.put("communityId", communityId.toString());
            manifest.put("generatedAt", at.toString());
            manifest.put("format", "One CSV per table. UTF-8. Header row. Timestamps are UTC ISO-8601. Amounts are plain decimals.");
            manifest.put("rows", rows);
            write(zip, json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            write(zip, readme(communityName, at, rows));
            zip.closeEntry();
        }
        return new Built(target, Files.size(target), rows);
    }

    private static String readme(String communityName, Instant at, Map<String, Long> rows) {
        StringBuilder out = new StringBuilder();
        out.append("Amanah Connect: all data of ").append(communityName).append("\r\n");
        out.append("Prepared ").append(at).append(" (UTC)\r\n\r\n");
        out.append("Every file is a table, as CSV (UTF-8, first line = column names). Open it in any spreadsheet.\r\n");
        out.append("- Times are UTC, ISO-8601. Amounts are plain decimals in the community's currency.\r\n");
        out.append("- Text that starts with = + - @ is shown with a leading apostrophe so a spreadsheet does not run it as a formula.\r\n");
        out.append("- Not included: password hashes and secret tokens, and uploaded files (logo, ledger attachments, receipt PDFs). Receipts can be re-created from receipts.csv and payment_records.csv.\r\n");
        out.append("- members.csv shows erased members as \"Erased member\" with their personal fields empty.\r\n\r\n");
        out.append("Tables and number of rows:\r\n");
        rows.forEach((table, n) -> out.append("  ").append(table).append(".csv  ").append(n).append("\r\n"));
        return out.toString();
    }

    private static void write(OutputStream out, String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
