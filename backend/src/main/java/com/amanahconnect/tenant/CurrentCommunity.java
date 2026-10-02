package com.amanahconnect.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the caller's community into a controller method, either as a {@link CurrentTenant} or as
 * the community {@link java.util.UUID}. It is derived from the authenticated principal, so a controller
 * never has to (and never may) read a community id from the path, query or body.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentCommunity {}
