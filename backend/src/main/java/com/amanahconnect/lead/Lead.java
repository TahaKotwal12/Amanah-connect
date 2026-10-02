package com.amanahconnect.lead;

import com.amanahconnect.common.persistence.BaseEntity;
import com.amanahconnect.common.persistence.CitextJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;

/** A demo request from the public site. Platform data, not tenant data. */
@Entity
@Table(name = "leads")
@Getter
@Setter
public class Lead extends BaseEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @JdbcType(CitextJdbcType.class)
    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "community_name", length = 200)
    private String communityName;

    @Column(name = "size_estimate")
    private Integer sizeEstimate;

    @Column(name = "message")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeadStatus status = LeadStatus.NEW;

    @Column(name = "handled_by")
    private UUID handledBy;

    @Column(name = "source", nullable = false, length = 50)
    private String source = "WEBSITE";
}
