package com.amanahconnect.file;

import com.amanahconnect.plan.StorageUsageProvider;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The real storage usage: the bytes of the community's recorded files. */
@Component
class FileStorageUsage implements StorageUsageProvider {

    private final StoredFileRepository files;

    FileStorageUsage(StoredFileRepository files) {
        this.files = files;
    }

    @Override
    public long usedBytes(UUID communityId) {
        return files.sumLiveBytesByCommunityId(communityId);
    }
}
