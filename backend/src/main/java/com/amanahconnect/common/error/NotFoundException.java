package com.amanahconnect.common.error;

/**
 * The resource does not exist <em>for the caller</em>. Used for both "no such id" and "exists in another
 * community": the message and body are identical, so a response never reveals that another tenant's
 * row exists (cross-tenant access answers 404, never 403).
 */
public class NotFoundException extends ApiException {

    public NotFoundException() {
        super(ErrorCode.NOT_FOUND, "The requested resource was not found.");
    }
}
