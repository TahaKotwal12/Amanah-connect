package com.amanahconnect.tenant;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Base of every table that belongs to one community ({@code community_id NOT NULL}). The Hibernate
 * {@code tenantFilter} declared here applies to all subclasses (see {@link TenantFilters}).
 */
@MappedSuperclass
@FilterDef(
        name = TenantFilters.TENANT_FILTER,
        parameters = @ParamDef(name = TenantFilters.PARAMETER, type = UUID.class))
@Filter(name = TenantFilters.TENANT_FILTER, condition = "community_id = :communityId")
@Getter
@Setter
public abstract class TenantEntity extends BaseEntity {

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;
}
