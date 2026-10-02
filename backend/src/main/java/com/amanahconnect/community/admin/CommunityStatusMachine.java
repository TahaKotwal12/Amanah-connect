package com.amanahconnect.community.admin;

import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.community.CommunityStatus;

/**
 * Allowed community status changes.
 *
 * <pre>
 *   PENDING  -> ACTIVE (activate, or owner accepts the invitation) | SUSPENDED | ARCHIVED
 *   ACTIVE   -> SUSPENDED | ARCHIVED
 *   SUSPENDED-> ACTIVE (activate) | ARCHIVED
 *   ARCHIVED -> ACTIVE (activate: a deliberate restore)
 * </pre>
 */
public final class CommunityStatusMachine {

    private CommunityStatusMachine() {}

    public static CommunityStatus suspend(CommunityStatus from) {
        if (from == CommunityStatus.ACTIVE || from == CommunityStatus.PENDING) {
            return CommunityStatus.SUSPENDED;
        }
        throw refused(from, "suspended");
    }

    public static CommunityStatus activate(CommunityStatus from) {
        if (from != CommunityStatus.ACTIVE) {
            return CommunityStatus.ACTIVE;
        }
        throw refused(from, "activated");
    }

    public static CommunityStatus archive(CommunityStatus from) {
        if (from != CommunityStatus.ARCHIVED) {
            return CommunityStatus.ARCHIVED;
        }
        throw refused(from, "archived");
    }

    private static ApiException refused(CommunityStatus from, String verb) {
        return new ApiException(
                ErrorCode.INVALID_STATE_TRANSITION,
                "A community that is " + from.name().toLowerCase() + " cannot be " + verb + ".");
    }
}
