package com.amanahconnect.notification;

import com.amanahconnect.common.persistence.BaseEntity;
import com.amanahconnect.common.persistence.CitextJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An address the app never emails again (hard bounce, spam complaint, or added by hand). Global: it is about the address, not a community. */
@Entity
@Table(name = "email_suppressions")
@Getter
@Setter
public class EmailSuppression extends BaseEntity {

    @JdbcType(CitextJdbcType.class)
    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private SuppressionReason reason;

    @Column(name = "source", nullable = false, length = 30)
    private String source = "SES";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail = new HashMap<>();
}
