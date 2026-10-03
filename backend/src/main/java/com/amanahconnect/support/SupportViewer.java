package com.amanahconnect.support;

import java.util.UUID;

/** Who is looking at the helpdesk: one community's admins (scoped to it), or the platform's super admins (all of it). */
public record SupportViewer(SupportSide side, UUID communityId) {

    public static SupportViewer community(UUID communityId) {
        if (communityId == null) throw new IllegalArgumentException("a community viewer needs a community");
        return new SupportViewer(SupportSide.COMMUNITY, communityId);
    }

    public static SupportViewer platform() {
        return new SupportViewer(SupportSide.PLATFORM, null);
    }

    public boolean isPlatform() {
        return side == SupportSide.PLATFORM;
    }
}
