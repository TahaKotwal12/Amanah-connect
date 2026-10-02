package com.amanahconnect.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The request path as the application sees it: percent-decoded and normalised by the container.
 *
 * <p>Security filters must match on this, never on {@code getRequestURI()}. The raw URI still contains
 * escapes, so {@code /api/v1/auth/%72efresh} would not equal {@code /api/v1/auth/refresh} in a string
 * comparison, yet Spring decodes it and routes it to the same handler, silently skipping any filter
 * that compared the raw string.
 */
public final class RequestPaths {

    private RequestPaths() {}

    public static String of(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? servletPath : servletPath + pathInfo;
    }
}
