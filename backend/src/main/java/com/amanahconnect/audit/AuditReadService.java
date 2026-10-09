package com.amanahconnect.audit;

import com.amanahconnect.audit.AuditDtos.AdminEntry;
import com.amanahconnect.audit.AuditDtos.CommunityEntry;
import com.amanahconnect.audit.AuditDtos.Filter;
import com.amanahconnect.audit.AuditDtos.Page;
import com.amanahconnect.common.csv.CsvWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Reading the audit trail for the platform (everything) and for a community (its own), and exporting it. Reading never changes the trail; exporting is itself recorded. */
@Service
public class AuditReadService {

    /** What an export will contain, decided and recorded before the first byte is sent. */
    public record ExportPlan(int rows, boolean truncated, Filter filter) {}

    private final AuditQueries queries;
    private final AuditService audit;
    private final TransactionTemplate readOnly;
    private final JsonMapper json;
    private final int exportLimit;
    private final java.time.Clock clock;

    public AuditReadService(AuditQueries queries, AuditService audit, PlatformTransactionManager transactionManager, JsonMapper json, AuditProperties properties, java.time.Clock clock) {
        this.queries = queries;
        this.audit = audit;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.json = json;
        this.exportLimit = properties.exportLimit();
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AdminEntry> admin(Filter filter, String cursor, Integer limit) {
        return queries.adminPage(filter, cursor, limit == null ? AuditQueries.DEFAULT_LIMIT : limit);
    }

    @Transactional(readOnly = true)
    public Page<CommunityEntry> community(UUID communityId, Filter filter, String cursor, Integer limit) {
        return queries.communityPage(communityId, filter, cursor, limit == null ? AuditQueries.DEFAULT_LIMIT : limit);
    }

    /**
     * Decides what the export will contain and writes the audit entry for it. The export is "everything that matched up to this moment": the entry
     * written here is newer than that moment, so it is never part of the export it records.
     */
    @Transactional
    public ExportPlan prepareExport(Filter requested) {
        java.time.Instant upTo = clock.instant();
        java.time.Instant end = requested.toExclusive() == null || requested.toExclusive().isAfter(upTo) ? upTo : requested.toExclusive();
        Filter filter = new Filter(requested.actor(), requested.communityId(), requested.action(), requested.actionPrefix(), requested.entityType(), requested.entityId(), requested.from(), end);
        int matching = queries.countUpTo(filter, exportLimit);
        boolean truncated = matching > exportLimit;
        int rows = Math.min(matching, exportLimit);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("rows", rows);
        after.put("truncated", truncated);
        after.put("filter", describe(requested));
        audit.record("AUDIT_EXPORTED", "AuditLog", null, null, after);
        return new ExportPlan(rows, truncated, filter);
    }

    /** Writes the CSV, one row at a time, straight to the response: memory use does not grow with the export. */
    public void writeCsv(ExportPlan plan, OutputStream out) {
        readOnly.executeWithoutResult(status -> {
            try {
                Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                writer.write('﻿'); // so Excel reads UTF-8
                writer.write(new CsvWriter().row("id", "time_utc", "action", "summary", "actor_id", "actor_name", "actor_role", "community_id", "community_name", "entity_type", "entity_id", "before", "after", "ip", "user_agent", "request_id").toString());
                queries.stream(plan.filter(), plan.rows(), e -> {
                    try {
                        writer.write(new CsvWriter().row(e.id(), e.at(), e.action(), e.summary(), e.actorId(), e.actorName(), e.actorRole(), e.communityId(), e.communityName(), e.entityType(), e.entityId(),
                                text(e.before()), text(e.after()), e.ip(), e.userAgent(), e.requestId()).toString());
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
                writer.flush();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    private String text(Map<String, Object> value) {
        return value == null ? "" : json.writeValueAsString(value);
    }

    private static Map<String, Object> describe(Filter f) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (f.actor() != null) m.put("actor", f.actor().toString());
        if (f.communityId() != null) m.put("communityId", f.communityId().toString());
        if (f.action() != null) m.put("action", f.action());
        if (f.actionPrefix() != null) m.put("actionPrefix", f.actionPrefix());
        if (f.entityType() != null) m.put("entityType", f.entityType());
        if (f.entityId() != null) m.put("entityId", f.entityId().toString());
        if (f.from() != null) m.put("from", f.from().toString());
        if (f.toExclusive() != null) m.put("to", f.toExclusive().toString());
        return m;
    }
}
