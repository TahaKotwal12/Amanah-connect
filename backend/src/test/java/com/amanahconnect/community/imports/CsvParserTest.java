package com.amanahconnect.community.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CsvParserTest {

    private final CsvParser parser = new CsvParser(new ImportProperties(5, 1024));

    private CsvTable parse(String text) {
        return parser.parse("members", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void normalisesHeadersAndNumbersRowsLikeASpreadsheet() {
        CsvTable table = parse("﻿Member Number, Full-Name ,Mobile\nM1,Asha,12345\n\nM2,\"Rao, Ravi\",\n");
        assertThat(table.headers()).containsExactly("member_no", "full_name", "phone");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).number()).isEqualTo(2);
        assertThat(table.rows().get(1).get("full_name")).isEqualTo("Rao, Ravi");
        assertThat(table.rows().get(1).number()).as("blank lines are skipped and not counted").isEqualTo(3);
        assertThat(table.rows().get(0).get("missing")).isEmpty();
    }

    @Test
    void detectsSemicolonAndTabSeparators() {
        assertThat(parse("member_no;full_name\nM1;Asha\n").rows().get(0).get("full_name")).isEqualTo("Asha");
        assertThat(parse("member_no\tfull_name\nM1\tAsha\n").rows().get(0).get("full_name")).isEqualTo("Asha");
    }

    @Test
    void skipsRowsThatAreAllBlank() {
        assertThat(parse("member_no,full_name\n,\nM1,Asha\n , \n").rows()).hasSize(1);
    }

    @Test
    void enforcesTheRowLimit() {
        StringBuilder text = new StringBuilder("member_no,full_name\n");
        for (int i = 0; i < 6; i++) text.append("M").append(i).append(",N\n");
        assertThatThrownBy(() -> parse(text.toString())).isInstanceOf(ApiException.class).hasMessageContaining("more than 5 rows");
    }

    @Test
    void enforcesTheSizeLimit() {
        assertThatThrownBy(() -> parser.parse("members", new byte[2048])).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void refusesWorkbooksBinaryAndNonUtf8() {
        assertThatThrownBy(() -> parser.parse("members", new byte[] {'P', 'K', 3, 4, 1})).hasMessageContaining("Excel");
        assertThatThrownBy(() -> parser.parse("members", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1})).hasMessageContaining("Excel");
        assertThatThrownBy(() -> parser.parse("members", "%PDF-1.7".getBytes(StandardCharsets.UTF_8))).hasMessageContaining("PDF");
        assertThatThrownBy(() -> parser.parse("members", new byte[] {'a', 0, 'b'})).hasMessageContaining("not a text file");
        assertThatThrownBy(() -> parser.parse("members", "a,b\nJosé,x".getBytes(StandardCharsets.ISO_8859_1))).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> parse("")).hasMessageContaining("empty");
        assertThatThrownBy(() -> parse("   \n ")).hasMessageContaining("empty");
    }

    @Test
    void parsesTheThreeAcceptedDateFormatsStrictly() {
        assertThat(ImportValidator.date("2024-03-31")).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(ImportValidator.date("31/03/2024")).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(ImportValidator.date("31-03-2024")).isEqualTo(LocalDate.of(2024, 3, 31));
        for (String bad : new String[] {"31/02/2024", "2024-13-01", "03/31/2024", "1/1/24", "yesterday", "2024-3-1"}) {
            assertThat(ImportValidator.date(bad)).as(bad).isNull();
        }
    }
}
