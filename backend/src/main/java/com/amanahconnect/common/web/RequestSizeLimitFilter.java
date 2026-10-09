package com.amanahconnect.common.web;

import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses non-upload request bodies bigger than {@code app.limits.max-body-bytes} (default 1 MB) with 413 PAYLOAD_TOO_LARGE. Uploads
 * (multipart) have their own, larger limit in {@code spring.servlet.multipart}. A declared Content-Length is checked up front; a body sent
 * in chunks is counted as it is read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long max;
    private final ProblemResponseWriter problems;

    public RequestSizeLimitFilter(@Value("${app.limits.max-body-bytes:1048576}") long max, ProblemResponseWriter problems) {
        this.max = max;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > max) {
            problems.write(response, ErrorCode.PAYLOAD_TOO_LARGE, "The request body is too large.");
            return;
        }
        chain.doFilter(new Counting(request, max), response);
    }

    /** Thrown from the request stream; the JSON reader wraps it, and GlobalExceptionHandler turns it back into a 413. */
    public static final class BodyTooLargeException extends IOException {
        public BodyTooLargeException() {
            super("The request body is too large.");
        }
    }

    private static final class Counting extends HttpServletRequestWrapper {
        private final long max;

        Counting(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                private void count(long n) throws IOException {
                    read += n;
                    if (read > max) throw new BodyTooLargeException();
                }

                @Override public int read() throws IOException { int b = in.read(); if (b >= 0) count(1); return b; }
                @Override public int read(byte[] buf, int off, int len) throws IOException { int n = in.read(buf, off, len); if (n > 0) count(n); return n; }
                @Override public boolean isFinished() { return in.isFinished(); }
                @Override public boolean isReady() { return in.isReady(); }
                @Override public void setReadListener(ReadListener l) { in.setReadListener(l); }
            };
        }
    }
}
