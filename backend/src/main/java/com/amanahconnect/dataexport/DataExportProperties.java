package com.amanahconnect.dataexport;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Data export settings ({@code app.exports.*}).
 *
 * @param linkValidity how long the emailed download link works (the file is deleted afterwards)
 * @param maxPerDay how many exports a community may ask for in 24 hours
 * @param staleAfter a RUNNING export that has not finished by then is assumed dead (its worker crashed) and is picked up again
 * @param linkBaseUrl where the emailed link points (the public site; {@code /api/v1/public/exports/<token>} is appended). Empty means the frontend URL.
 */
@ConfigurationProperties(prefix = "app.exports")
public record DataExportProperties(
        @DefaultValue("PT48H") Duration linkValidity,
        @DefaultValue("3") int maxPerDay,
        @DefaultValue("PT30M") Duration staleAfter,
        @DefaultValue("") String linkBaseUrl) {}
