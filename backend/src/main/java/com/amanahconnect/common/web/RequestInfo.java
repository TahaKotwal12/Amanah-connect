package com.amanahconnect.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Who is calling: client address, user agent and request id, for audit records and token rows. */
public record RequestInfo(String ip, String userAgent, String requestId) {

    private static final int MAX_IP = 45;
    private static final int MAX_USER_AGENT = 512;

    public static RequestInfo of(HttpServletRequest request) {
        // getRemoteAddr() only: behind Nginx Tomcat rewrites it from X-Forwarded-For for trusted
        // proxies. Reading that header here would let any client choose its own IP.
        return new RequestInfo(
                truncate(request.getRemoteAddr(), MAX_IP),
                truncate(request.getHeader("User-Agent"), MAX_USER_AGENT),
                MDC.get(RequestIdFilter.MDC_KEY));
    }

    /** The current request, or an empty info outside a web request (jobs, startup runners). */
    public static RequestInfo current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return of(servlet.getRequest());
        }
        return new RequestInfo(null, null, MDC.get(RequestIdFilter.MDC_KEY));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
