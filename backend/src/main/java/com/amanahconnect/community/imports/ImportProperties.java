package com.amanahconnect.community.imports;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** CSV import limits ({@code app.imports.*}). */
@ConfigurationProperties(prefix = "app.imports")
public record ImportProperties(
        @DefaultValue("5000") int maxRowsPerFile,
        @DefaultValue("2097152") long maxFileBytes) {}
