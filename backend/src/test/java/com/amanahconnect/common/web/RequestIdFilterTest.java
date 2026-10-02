package com.amanahconnect.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesIdWhenHeaderMissing() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest(), new AtomicReference<>());

        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void reusesSafeIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123_X.y");
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        MockHttpServletResponse response = run(request, seenInMdc);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123_X.y");
        assertThat(seenInMdc.get()).isEqualTo("abc-123_X.y");
    }

    @Test
    void replacesUnsafeIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "evil\nINFO forged log line");

        MockHttpServletResponse response = run(request, new AtomicReference<>());

        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesOverlongIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "a".repeat(65));

        MockHttpServletResponse response = run(request, new AtomicReference<>());

        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void clearsMdcAfterRequest() throws Exception {
        run(new MockHttpServletRequest(), new AtomicReference<>());

        assertThat(MDC.get("requestId")).isNull();
    }

    private MockHttpServletResponse run(
            MockHttpServletRequest request, AtomicReference<String> seenInMdc) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> seenInMdc.set(MDC.get("requestId"));
        filter.doFilter(request, response, chain);
        return response;
    }
}
