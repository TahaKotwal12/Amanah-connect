package com.amanahconnect.community.imports;

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

/**
 * One import attempt for a community: the dry-run report, the validated rows it would apply, and (once
 * confirmed) the result. {@code batchId} is chosen by the client and makes the whole flow idempotent.
 */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
public class ImportBatch extends TenantEntity {

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    /** SHA-256 of the uploaded files, to tell a retry (same content) from a conflicting reuse of the id. */
    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImportBatchStatus status = ImportBatchStatus.DRY_RUN;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> report = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rows", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rows = new HashMap<>();

    @Column(name = "confirmable", nullable = false)
    private boolean confirmable;

    @Column(name = "skip_invalid")
    private Boolean skipInvalid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
