package com.amanahconnect.support;

/** Which end of the helpdesk wrote a message: a community admin, or the platform's super admins. */
public enum SupportSide {
    COMMUNITY,
    PLATFORM;

    public SupportSide other() {
        return this == COMMUNITY ? PLATFORM : COMMUNITY;
    }
}
