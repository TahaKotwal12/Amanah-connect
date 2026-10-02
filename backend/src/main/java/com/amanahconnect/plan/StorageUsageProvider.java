package com.amanahconnect.plan;

import java.util.UUID;

/** How many bytes of stored files a community uses. The file module supplies the real implementation. */
public interface StorageUsageProvider {

    long usedBytes(UUID communityId);
}
