package com.amanahconnect.audit;

import com.amanahconnect.audit.AuditDtos.Filter;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reads the filter parameters shared by the admin and community audit endpoints, refusing anything malformed with a 400. */
public final class AuditFilters {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,100}$");

    private AuditFilters() {}

    /**
     * @param from a date ({@code 2026-05-01}, start of that day in India) or an instant ({@code 2026-05-01T10:00:00Z})
     * @param to the same forms; a date means through the end of that day, an instant is exclusive
     */
    public static Filter of(UUID actor, UUID communityId, String action, String actionPrefix, String entityType, UUID entityId, String from, String to) {
        List<String> problems = new ArrayList<>();
        Instant start = parse("from", from, false, problems);
        Instant end = parse("to", to, true, problems);
        if (start != null && end != null && !end.isAfter(start)) problems.add("to: must be after from");
        name("action", action, problems);
        name("actionPrefix", actionPrefix, problems);
        name("entityType", entityType, problems);
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", problems);
        }
        return new Filter(actor, communityId, blank(action), blank(actionPrefix), blank(entityType), entityId, start, end);
    }

    private static Instant parse(String field, String value, boolean endOfDay, List<String> problems) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            if (v.length() == 10) {
                LocalDate date = LocalDate.parse(v);
                return (endOfDay ? date.plusDays(1) : date).atStartOfDay(IST).toInstant();
            }
            return Instant.parse(v);
        } catch (RuntimeException e) {
            problems.add(field + ": use a date (2026-05-01) or a UTC time (2026-05-01T10:00:00Z)");
            return null;
        }
    }

    private static void name(String field, String value, List<String> problems) {
        if (value != null && !value.isBlank() && !NAME.matcher(value.trim()).matches()) problems.add(field + ": letters, digits and underscores only");
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
