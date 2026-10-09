package com.amanahconnect.dataexport;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class DataExportDtos {

    private DataExportDtos() {}

    public record ExportView(
            UUID id, ExportStatus status, Instant requestedAt, Instant completedAt, Long sizeBytes, Map<String, Object> tables, Instant linkExpiresAt, int downloadCount, String error) {}

    /** A short-lived address to fetch the ZIP from; ask again for a fresh one. */
    public record DownloadUrl(String url, long validForSeconds) {}
}
