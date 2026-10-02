package com.amanahconnect.community.imports;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Reads an uploaded CSV. Refuses what is clearly not CSV (Excel workbooks, zip files, binary data, text that is not
 * UTF-8) with a clear message instead of importing garbage. Handles a UTF-8 byte-order mark and comma, semicolon or
 * tab separators (Excel in some locales saves semicolons).
 */
@Component
public class CsvParser {

    /** Spellings people use for the columns we know, mapped to the canonical names. */
    private static final Map<String, String> ALIASES =
            Map.ofEntries(
                    Map.entry("member_number", "member_no"),
                    Map.entry("member_id", "member_no"),
                    Map.entry("name", "full_name"),
                    Map.entry("member_name", "full_name"),
                    Map.entry("mobile", "phone"),
                    Map.entry("group_label", "group"),
                    Map.entry("joined", "joined_on"),
                    Map.entry("date_joined", "joined_on"),
                    Map.entry("email_consent", "consent_email"),
                    Map.entry("invoice_number", "invoice_no"),
                    Map.entry("due", "due_date"),
                    Map.entry("outstanding", "amount"),
                    Map.entry("date", "as_of"));

    private final ImportProperties properties;

    public CsvParser(ImportProperties properties) {
        this.properties = properties;
    }

    public CsvTable parse(String fileLabel, byte[] bytes) {
        if (bytes.length > properties.maxFileBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The %s file is larger than %d KB.".formatted(fileLabel, properties.maxFileBytes() / 1024));
        }
        rejectNonCsv(fileLabel, bytes);
        String text = decode(fileLabel, bytes);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw invalid("The %s file is empty.".formatted(fileLabel));
        }
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter(text)).setIgnoreEmptyLines(true).setTrim(false).get();
        List<String> headers = new ArrayList<>();
        List<CsvTable.Row> rows = new ArrayList<>();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            for (CSVRecord record : parser) {
                if (headers.isEmpty()) {
                    for (String cell : record) {
                        headers.add(normaliseHeader(cell));
                    }
                    continue;
                }
                if (allBlank(record)) {
                    continue;
                }
                if (rows.size() >= properties.maxRowsPerFile()) {
                    throw invalid("The %s file has more than %d rows. Split it into several files.".formatted(fileLabel, properties.maxRowsPerFile()));
                }
                Map<String, String> cells = new LinkedHashMap<>();
                for (int i = 0; i < headers.size() && i < record.size(); i++) {
                    cells.putIfAbsent(headers.get(i), record.get(i));
                }
                rows.add(new CsvTable.Row((int) record.getRecordNumber(), cells, record.size()));
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw invalid("The %s file could not be read as CSV: %s".formatted(fileLabel, e.getMessage()));
        }
        if (headers.isEmpty()) {
            throw invalid("The %s file has no header row.".formatted(fileLabel));
        }
        return new CsvTable(List.copyOf(headers), List.copyOf(rows));
    }

    static String normaliseHeader(String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_");
        return ALIASES.getOrDefault(name, name);
    }

    private static boolean allBlank(CSVRecord record) {
        for (String cell : record) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static char delimiter(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        long commas = first.chars().filter(c -> c == ',').count();
        long semicolons = first.chars().filter(c -> c == ';').count();
        long tabs = first.chars().filter(c -> c == '\t').count();
        if (semicolons > commas && semicolons >= tabs) return ';';
        if (tabs > commas && tabs > semicolons) return '\t';
        return ',';
    }

    private static void rejectNonCsv(String fileLabel, byte[] bytes) {
        boolean zip = bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4; // .xlsx, .zip
        boolean ole = bytes.length > 3 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0; // .xls
        boolean pdf = bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
        if (zip || ole || pdf) {
            throw invalid("The %s file is an Excel, zip or PDF file. Save it as CSV (UTF-8) and upload that.".formatted(fileLabel));
        }
        for (byte b : bytes) {
            if (b == 0) {
                throw invalid("The %s file is not a text file. Save it as CSV (UTF-8) and upload that.".formatted(fileLabel));
            }
        }
    }

    private static String decode(String fileLabel, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalid("The %s file is not UTF-8. In Excel choose Save As > CSV UTF-8.".formatted(fileLabel));
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, List.of(message));
    }
}
