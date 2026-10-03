package com.amanahconnect.file;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A file a community has in object storage; the sum of live rows is its storage usage. */
@Entity
@Table(name = "stored_files")
@Getter
@Setter
public class StoredFile extends TenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private StoredFileKind kind;

    @Column(name = "object_key", nullable = false, updatable = false, length = 255)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
