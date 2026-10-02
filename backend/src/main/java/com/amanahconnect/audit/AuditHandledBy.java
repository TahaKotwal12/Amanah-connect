package com.amanahconnect.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mutating controller method whose audit record is written by something other than
 * {@link Audited} (typically the service it calls), and says by what. It exists so that "every
 * mutating endpoint writes an audit log" can be checked mechanically: a mutating controller method with
 * neither annotation fails the build.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditHandledBy {

    /** Where the audit record is written, e.g. "AuthService records LOGIN_SUCCESS". */
    String value();
}
