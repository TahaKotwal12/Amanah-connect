package com.amanahconnect.plan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageUsageConfig {

    /**
     * Placeholder until the file module tracks usage: reports 0 bytes, so the storage limit cannot trip
     * yet. It is replaced automatically by any other {@link StorageUsageProvider} bean.
     */
    @Bean
    @ConditionalOnMissingBean(StorageUsageProvider.class)
    StorageUsageProvider untrackedStorageUsage() {
        return communityId -> 0L;
    }
}
