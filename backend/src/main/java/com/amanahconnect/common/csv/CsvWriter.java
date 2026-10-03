package com.amanahconnect.common.csv;

import java.util.List;

/**
 * Writes RFC 4180 CSV that is safe to open in a spreadsheet. A cell that starts with {@code =}, {@code +},
 * {@code -}, {@code @}, a tab or a carriage return would be run as a formula by Excel or Sheets, so such
 * cells get a leading apostrophe (CSV injection). Numbers the caller marks as plain are left alone.
 */
public final class CsvWriter {

    private final StringBuilder out = new StringBuilder();

    public CsvWriter row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            // Numbers, dates and booleans cannot be formulas (a negative amount is "-12.50", not an attack); text can.
            Object cell = cells[i];
            boolean typed = cell instanceof Number || cell instanceof java.time.temporal.TemporalAccessor || cell instanceof Boolean || cell instanceof com.amanahconnect.common.money.Money;
            out.append(escape(cell == null ? "" : cell.toString(), typed));
        }
        out.append("\r\n");
        return this;
    }

    public CsvWriter row(List<?> cells) {
        return row(cells.toArray());
    }

    /** A row whose value cell is a trusted number or date and must stay unprefixed (e.g. -12.50). */
    public CsvWriter rowWithPlainLast(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            boolean plain = i == cells.length - 1;
            out.append(escape(cells[i] == null ? "" : cells[i].toString(), plain));
        }
        out.append("\r\n");
        return this;
    }

    static String escape(String value, boolean trustedNumeric) {
        String cell = value;
        if (!trustedNumeric && !cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
        if (quote) {
            return "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        return cell;
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
