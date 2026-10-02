package com.amanahconnect.complaint;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "complaint_comments")
@Getter
@Setter
public class ComplaintComment extends TenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complaint_id", nullable = false, updatable = false)
    private Complaint complaint;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private UUID authorUserId;

    @Column(name = "body", nullable = false)
    private String body;

    /** Internal notes are for admins only and are never emailed to the member. */
    @Column(name = "internal", nullable = false)
    private boolean internal = true;
}
