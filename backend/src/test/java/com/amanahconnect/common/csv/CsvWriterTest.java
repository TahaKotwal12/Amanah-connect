package com.amanahconnect.common.csv;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void writesRfc4180RowsWithCrLf() {
        String csv = new CsvWriter().row("a", "b,c", "say \"hi\"", "line\nbreak", null).toString();
        assertThat(csv).isEqualTo("a,\"b,c\",\"say \"\"hi\"\"\",\"line\nbreak\",\r\n");
    }

    @Test
    void neutralisesSpreadsheetFormulas() {
        for (String dangerous : new String[] {"=1+1", "+91 98765", "-5", "@SUM(A1)", "\tcmd", "\rcmd"}) {
            assertThat(CsvWriter.escape(dangerous, false).replaceFirst("^\"", "")).as(dangerous).startsWith("'");
        }
        assertThat(CsvWriter.escape("normal", false)).isEqualTo("normal");
        assertThat(CsvWriter.escape("", false)).isEmpty();
    }

    @Test
    void aTrustedNumberKeepsItsSign() {
        assertThat(new CsvWriter().rowWithPlainLast("label", "-12.50").toString()).isEqualTo("label,-12.50\r\n");
        assertThat(new CsvWriter().rowWithPlainLast("=evil", "-12.50").toString()).startsWith("'=evil,");
    }
}
