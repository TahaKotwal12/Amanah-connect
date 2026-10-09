package com.amanahconnect.dataexport;

import com.amanahconnect.audit.AuditService;
import com.amanahconnect.auth.Tokens;
import com.amanahconnect.auth.UserRepository;
import com.amanahconnect.auth.AuthProperties;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.NotFoundException;
import com.amanahconnect.common.error.RateLimitedException;
import com.amanahconnect.community.CommunityRepository;
import com.amanahconnect.dataexport.DataExportDtos.DownloadUrl;
import com.amanahconnect.dataexport.DataExportDtos.ExportView;
import com.amanahconnect.file.ObjectStorage;
import com.amanahconnect.file.StorageProperties;
import com.amanahconnect.notification.EmailOutbox;
import com.amanahconnect.notification.EmailOutboxRepository;
import com.amanahconnect.tenant.TenantGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A community's data as a ZIP of CSVs. Asking is instant (a row in {@code data_exports}); a worker builds the ZIP later (so the request never waits
 * and a restart loses nothing), stores it in S3 under {@code communities/<id>/exports/}, and emails the person who asked a link that works for
 * {@code app.exports.link-validity} (48 h). The link carries a random token of which only the hash is kept; the ZIP is deleted when the link expires.
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);
    static final String EMAIL_TEMPLATE = "data-export-ready";
    private static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(5);

    public record WorkReport(int built, int failed, int expired) {}

    private final DataExportRepository exports;
    private final ExportBuilder builder;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final EmailOutboxRepository outbox;
    private final UserRepository users;
    private final CommunityRepository communities;
    private final AuthProperties authProperties;
    private final DataExportProperties properties;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final TenantGuard tenantGuard;
    private final TransactionTemplate transaction;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    public DataExportService(
            DataExportRepository exports, ExportBuilder builder, ObjectStorage storage, StorageProperties storageProperties, EmailOutboxRepository outbox, UserRepository users,
            CommunityRepository communities, AuthProperties authProperties, DataExportProperties properties, NamedParameterJdbcTemplate jdbc, AuditService audit, TenantGuard tenantGuard,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.exports = exports;
        this.builder = builder;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.outbox = outbox;
        this.users = users;
        this.communities = communities;
        this.authProperties = authProperties;
        this.properties = properties;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tenantGuard = tenantGuard;
        this.transaction = new TransactionTemplate(transactionManager);
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); // every CSV describes the same moment
        this.clock = clock;
    }

    // ---- asking ------------------------------------------------------------------------------------------------------------

    @Transactional
    public ExportView request(UUID communityId) {
        tenantGuard.requireWritable();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))", new MapSqlParameterSource("k", "data-export:" + communityId), rs -> {});
        if (exports.countByCommunityIdAndStatusIn(communityId, List.of(ExportStatus.PENDING, ExportStatus.RUNNING)) > 0) {
            throw new ApiException(ErrorCode.EXPORT_IN_PROGRESS, "A data export is already being prepared. You will get an email when it is ready.");
        }
        if (exports.countByCommunityIdAndCreatedAtGreaterThanEqual(communityId, clock.instant().minus(Duration.ofHours(24))) >= properties.maxPerDay()) {
            throw new RateLimitedException(3600);
        }
        DataExport export = new DataExport();
        export.setCommunityId(communityId);
        export.setRequestedBy(AuditService.currentActorId());
        exports.save(export);
        audit.record("DATA_EXPORT_REQUESTED", "DataExport", export.getId(), null, Map.of("exportId", export.getId().toString()));
        return view(export);
    }

    @Transactional(readOnly = true)
    public List<ExportView> list(UUID communityId) {
        return exports.findByCommunityIdOrderByCreatedAtDesc(communityId, org.springframework.data.domain.PageRequest.of(0, 20)).stream().map(DataExportService::view).toList();
    }

    @Transactional(readOnly = true)
    public ExportView get(UUID communityId, UUID id) {
        return view(tenantGuard.found(exports.findByIdAndCommunityId(id, communityId)));
    }

    /** A fresh short-lived address for a ready export, for a signed-in admin of the community (the emailed link is the one without sign-in). */
    @Transactional
    public DownloadUrl downloadUrl(UUID communityId, UUID id) {
        DataExport export = tenantGuard.found(exports.findByIdAndCommunityId(id, communityId));
        if (export.getStatus() != ExportStatus.READY || export.getLinkExpiresAt() == null || !export.getLinkExpiresAt().isAfter(clock.instant())) {
            throw new NotFoundException();
        }
        audit.record("DATA_EXPORT_DOWNLOADED", "DataExport", id, null, Map.of("via", "signed-in"));
        return new DownloadUrl(storage.presignDownload(export.getObjectKey()), DOWNLOAD_URL_TTL.toSeconds());
    }

    // ---- the emailed link (no sign-in) ----------------------------------------------------------------------------------

    /**
     * @return where to send the browser (a short-lived storage address)
     * @throws ApiException EXPORT_LINK_UNAVAILABLE (404) for a link that is unknown, expired or whose file is gone: never says which
     */
    @Transactional
    public String openLink(String token) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{43}$")) throw unavailable();
        DataExport export = exports.findByTokenHash(Tokens.sha256Hex(token)).orElseThrow(DataExportService::unavailable);
        if (export.getStatus() != ExportStatus.READY || export.getLinkExpiresAt() == null || !export.getLinkExpiresAt().isAfter(clock.instant())) throw unavailable();
        export.setDownloadCount(export.getDownloadCount() + 1);
        exports.save(export);
        audit.record("DATA_EXPORT_DOWNLOADED", null, export.getCommunityId(), "DataExport", export.getId(), null, Map.of("via", "emailed link", "count", export.getDownloadCount()));
        return storage.presignDownload(export.getObjectKey());
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.EXPORT_LINK_UNAVAILABLE, "This download link is not valid.");
    }

    // ---- the worker ----------------------------------------------------------------------------------------------------

    /** Builds the exports that are waiting and expires the ones whose link has run out. Safe to run on several instances at once. */
    public WorkReport work() {
        int built = 0, failed = 0;
        for (UUID id : claim()) {
            if (build(id)) built++;
            else failed++;
        }
        return new WorkReport(built, failed, expire());
    }

    private List<UUID> claim() {
        Instant now = clock.instant();
        return transaction.execute(status -> jdbc.queryForList(
                "UPDATE data_exports SET status = 'RUNNING', started_at = :now, error = NULL WHERE id IN ("
                        + "SELECT id FROM data_exports WHERE status = 'PENDING' OR (status = 'RUNNING' AND started_at < :stale) ORDER BY created_at LIMIT 2 FOR UPDATE SKIP LOCKED) RETURNING id",
                new MapSqlParameterSource("now", Timestamp.from(now)).addValue("stale", Timestamp.from(now.minus(properties.staleAfter()))), UUID.class));
    }

    private boolean build(UUID id) {
        Path temp = null;
        try {
            DataExport export = transaction.execute(s -> exports.findForWorker(id).orElseThrow());
            String communityName = communities.findById(export.getCommunityId()).orElseThrow().getName();
            Instant at = clock.instant();
            temp = Files.createTempFile("amanah-export-", ".zip");
            Path target = temp;
            ExportBuilder.Built built = snapshot.execute(s -> {
                try {
                    return builder.build(export.getCommunityId(), communityName, at, target);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            String key = "communities/" + export.getCommunityId() + "/exports/" + UUID.randomUUID() + ".zip";
            storage.putFile(key, temp, "application/zip");
            finish(id, key, built);
            return true;
        } catch (RuntimeException | IOException e) {
            log.error("Data export {} failed: {}", id, e.toString());
            fail(id, e);
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Could not delete temporary export file {}", temp);
                }
            }
        }
    }

    private void finish(UUID id, String key, ExportBuilder.Built built) {
        transaction.executeWithoutResult(s -> {
            DataExport export = exports.findForWorker(id).orElseThrow();
            String token = Tokens.newOpaqueToken();
            Instant now = clock.instant();
            export.setStatus(ExportStatus.READY);
            export.setObjectKey(key);
            export.setSizeBytes(built.sizeBytes());
            export.setTables(new LinkedHashMap<>(built.rows()));
            export.setTokenHash(Tokens.sha256Hex(token));
            export.setLinkExpiresAt(now.plus(properties.linkValidity()));
            export.setCompletedAt(now);
            exports.save(export);
            var community = communities.findById(export.getCommunityId()).orElseThrow();
            var requester = users.findById(export.getRequestedBy());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("sizeBytes", built.sizeBytes());
            after.put("tables", built.rows().size());
            after.put("rows", built.rows().values().stream().mapToLong(Long::longValue).sum());
            audit.record("DATA_EXPORT_COMPLETED", null, export.getCommunityId(), "DataExport", id, null, after);
            requester.filter(u -> u.getStatus() == com.amanahconnect.auth.UserStatus.ACTIVE).ifPresent(u -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("fullName", u.getFullName());
                payload.put("communityName", community.getName());
                payload.put("link", linkBase() + "/api/v1/public/exports/" + token);
                payload.put("expiresAt", export.getLinkExpiresAt().toString());
                payload.put("hours", properties.linkValidity().toHours());
                payload.put("sizeText", sizeText(built.sizeBytes()));
                EmailOutbox mail = new EmailOutbox();
                mail.setToEmail(u.getEmail());
                mail.setTemplate(EMAIL_TEMPLATE);
                mail.setPayload(payload);
                outbox.save(mail);
            });
        });
    }

    private void fail(UUID id, Exception e) {
        try {
            transaction.executeWithoutResult(s -> {
                DataExport export = exports.findForWorker(id).orElse(null);
                if (export == null) return;
                String message = String.valueOf(e.getMessage());
                export.setStatus(ExportStatus.FAILED);
                export.setError(message.length() > 500 ? message.substring(0, 500) : message);
                export.setCompletedAt(clock.instant());
                exports.save(export);
                audit.record("DATA_EXPORT_FAILED", null, export.getCommunityId(), "DataExport", id, null, Map.of("error", export.getError()));
            });
        } catch (RuntimeException inner) {
            log.error("Data export {} could not even be marked failed: {}", id, inner.toString());
        }
    }

    /** Deletes the ZIP of every export whose link has run out. */
    private int expire() {
        Instant now = clock.instant();
        List<Map<String, Object>> due = jdbc.queryForList("SELECT id, community_id, object_key FROM data_exports WHERE status = 'READY' AND link_expires_at <= :now ORDER BY link_expires_at LIMIT 50",
                new MapSqlParameterSource("now", Timestamp.from(now)));
        int expired = 0;
        for (Map<String, Object> row : due) {
            UUID id = (UUID) row.get("id");
            try {
                storage.delete((String) row.get("object_key"));
                transaction.executeWithoutResult(s -> {
                    jdbc.update("UPDATE data_exports SET status = 'EXPIRED', token_hash = NULL WHERE id = :id AND status = 'READY'", new MapSqlParameterSource("id", id));
                    audit.record("DATA_EXPORT_EXPIRED", null, (UUID) row.get("community_id"), "DataExport", id, null, Map.of("exportId", id.toString()));
                });
                expired++;
            } catch (RuntimeException e) {
                log.warn("Could not expire data export {}: {}", id, e.toString());
            }
        }
        return expired;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private String linkBase() {
        String base = properties.linkBaseUrl().isBlank() ? authProperties.frontendBaseUrl() : properties.linkBaseUrl();
        return base.replaceAll("/+$", "");
    }

    static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " bytes";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ENGLISH, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static ExportView view(DataExport e) {
        boolean live = e.getStatus() == ExportStatus.READY;
        return new ExportView(e.getId(), e.getStatus(), e.getCreatedAt(), e.getCompletedAt(), e.getSizeBytes(), e.getTables(), live ? e.getLinkExpiresAt() : null, e.getDownloadCount(), e.getError());
    }
}
