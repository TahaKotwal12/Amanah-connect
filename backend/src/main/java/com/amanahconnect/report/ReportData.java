package com.amanahconnect.report;

import java.util.List;

/**
 * A finished report, independent of how it is shown: a title, a few context lines, columns, rows and an optional
 * totals row. The same data becomes CSV or PDF, so the two can never disagree.
 *
 * <p>Cells are {@code String}, {@code BigDecimal} (an amount), {@code Long} (a count), {@code java.time.LocalDate} or null.
 */
public record ReportData(String key, String title, List<String> context, List<Column> columns, List<Row> rows, Row totals, String currency) {

    public enum Align { LEFT, RIGHT }

    public record Column(String name, Align align, boolean amount) {
        static Column text(String name) {
            return new Column(name, Align.LEFT, false);
        }

        static Column amount(String name) {
            return new Column(name, Align.RIGHT, true);
        }

        static Column count(String name) {
            return new Column(name, Align.RIGHT, false);
        }
    }

    /** @param emphasised a subtotal or total line, shown in bold */
    public record Row(List<Object> cells, boolean emphasised) {
        static Row of(Object... cells) {
            return new Row(java.util.Arrays.asList(cells), false);
        }

        static Row bold(Object... cells) {
            return new Row(java.util.Arrays.asList(cells), true);
        }
    }
}
