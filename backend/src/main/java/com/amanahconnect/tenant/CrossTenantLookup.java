package com.amanahconnect.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the rare repository method on a tenant table that deliberately does NOT take a community id,
 * with the reason. Examples: resolving the community from a user or from an invite token, and the
 * super-admin's cross-community reports. An architecture test rejects any other lookup that lacks a
 * community id.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CrossTenantLookup {

    String value();
}
