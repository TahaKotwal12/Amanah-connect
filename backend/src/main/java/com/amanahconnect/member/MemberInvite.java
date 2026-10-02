package com.amanahconnect.member;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "member_invites")
@Getter
@Setter
public class MemberInvite extends BaseEntity {

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;

    /** Hex SHA-256 of the invite token; the raw token is shown once and never stored. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "max_uses", nullable = false)
    private int maxUses = 1;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;
}
