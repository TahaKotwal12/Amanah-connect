package com.amanahconnect.dataexport;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A request for all of a community's data as a ZIP of CSV files, and where that stands. */
@Entity
@Table(name = "data_exports")
@Getter
@Setter
public class DataExport extends TenantEntity {

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExportStatus status = ExportStatus.PENDING;

    @Column(name = "object_key", length = 255)
    private String objectKey;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Table name to number of rows exported. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tables", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> tables = new HashMap<>();

    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "link_expires_at")
    private Instant linkExpiresAt;

    @Column(name = "download_count", nullable = false)
    private int downloadCount;

    @Column(name = "error")
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
