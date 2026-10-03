package com.amanahconnect.notification;

/** What happened when an email to one person was considered. Only QUEUED put something in the outbox. */
public enum Delivery {
    QUEUED,
    NO_ADDRESS,
    NO_CONSENT,
    QUOTA
}
