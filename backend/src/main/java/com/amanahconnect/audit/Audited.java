package com.amanahconnect.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Writes an audit record when the annotated method returns normally. The action, the actor, the
 * community, the client address and the request id are captured automatically, and the return value
 * (a DTO, never an entity with secrets; secrets are redacted regardless) becomes the {@code after}
 * snapshot.
 *
 * <p>The method and its audit row commit together or not at all: the aspect opens (or joins) a
 * transaction around the call, so an audit failure rolls the change back. If a method needs a
 * {@code before} snapshot, call {@link AuditService#record} explicitly instead.
 *
 * <p>Put it on a service method or a controller method. Controllers that mutate data must carry either
 * this or {@link AuditHandledBy}; an architecture test enforces it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** Stable action name, e.g. {@code MEMBER_CREATED}. Never rename one once released. */
    String action();

    /** Entity type; defaults to the simple name of the returned object. */
    String entityType() default "";
}
