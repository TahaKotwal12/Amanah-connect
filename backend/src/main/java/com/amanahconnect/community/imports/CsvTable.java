package com.amanahconnect.community.imports;

import java.util.List;
import java.util.Map;

/** A parsed CSV file: normalised header names and data rows keyed by their spreadsheet row number (header = row 1). */
public record CsvTable(List<String> headers, List<Row> rows) {

    public record Row(int number, Map<String, String> cells, int cellCount) {

        /** The trimmed cell, or "" when the column is absent or empty. */
        public String get(String column) {
            String value = cells.get(column);
            return value == null ? "" : value.trim();
        }
    }
}
