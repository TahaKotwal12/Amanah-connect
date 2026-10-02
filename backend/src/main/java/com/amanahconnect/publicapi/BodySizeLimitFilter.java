package com.amanahconnect.publicapi;

import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.ProblemResponseWriter;
import com.amanahconnect.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request bodies on the unauthenticated {@code /api/v1/public/**} endpoints. A declared Content-Length over the
 * limit gets 413 before anything is read; a body without a declared length (chunked) is cut off while being read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 16 * 1024;

    private final ProblemResponseWriter problems;

    public BodySizeLimitFilter(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith("/api/v1/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BYTES) {
            problems.write(response, ErrorCode.PAYLOAD_TOO_LARGE, "The request body is too large.");
            return;
        }
        chain.doFilter(declared < 0 ? new LimitedRequest(request) : request, response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0 && ++read > MAX_BYTES) throw new IOException("Request body too large");
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int n = delegate.read(buffer, off, len);
                    if (n > 0 && (read += n) > MAX_BYTES) throw new IOException("Request body too large");
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
