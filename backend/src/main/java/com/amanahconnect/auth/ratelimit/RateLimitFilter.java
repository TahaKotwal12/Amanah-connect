package com.amanahconnect.auth.ratelimit;

import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.ProblemResponseWriter;
import com.amanahconnect.common.error.RateLimitedException;
import com.amanahconnect.common.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP limits on the abuse-prone public endpoints: login and 2FA (10/min), forgot-password and the
 * public lead form (5/hour). The per-email login limit lives in the login service, where the email is
 * known. Runs right after the request-id filter so a 429 still carries the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final java.util.regex.Pattern INVITE_VIEW = java.util.regex.Pattern.compile("^/api/v1/public/invites/[^/]+$");
    private static final java.util.regex.Pattern PAY_VIEW = java.util.regex.Pattern.compile("^/api/v1/public/pay/[^/]+$");
    private static final java.util.regex.Pattern INVITE_REGISTER = java.util.regex.Pattern.compile("^/api/v1/public/invites/[^/]+/register$");

    private final RateLimitService limiter;
    private final RateLimitProperties limits;
    private final ProblemResponseWriter problems;

    public RateLimitFilter(RateLimitService limiter, RateLimitProperties limits, ProblemResponseWriter problems) {
        this.limiter = limiter;
        this.limits = limits;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestPath = RequestPaths.of(request);
        boolean payView = "GET".equals(request.getMethod()) && PAY_VIEW.matcher(requestPath).matches();
        if ("GET".equals(request.getMethod()) && (INVITE_VIEW.matcher(requestPath).matches() || payView)) {
            try {
                if (payView) {
                    limiter.consume("pay-view-ip:" + request.getRemoteAddr(), limits.payViewPerIpPerMinute(), MINUTE);
                } else {
                    limiter.consume("invite-view-ip:" + request.getRemoteAddr(), limits.inviteViewPerIpPerMinute(), MINUTE);
                }
            } catch (RateLimitedException e) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
                problems.write(response, ErrorCode.RATE_LIMITED, e.getMessage());
                return;
            }
        }
        if ("POST".equals(request.getMethod())) {
            String ip = request.getRemoteAddr();
            try {
                if (INVITE_REGISTER.matcher(requestPath).matches()) {
                    limiter.consume("invite-register-ip:" + ip, limits.inviteRegisterPerIpPerHour(), HOUR);
                }
                switch (requestPath) {
                    case "/api/v1/auth/login" ->
                            limiter.consume("login-ip:" + ip, limits.loginPerIpPerMinute(), MINUTE);
                    case "/api/v1/auth/login/2fa" ->
                            limiter.consume("mfa-ip:" + ip, limits.mfaPerIpPerMinute(), MINUTE);
                    case "/api/v1/auth/password/forgot" ->
                            limiter.consume("forgot-ip:" + ip, limits.forgotPasswordPerIpPerHour(), HOUR);
                    case "/api/v1/public/leads" ->
                            limiter.consume("lead-ip:" + ip, limits.leadPerIpPerHour(), HOUR);
                    default -> {
                        // not rate limited here
                    }
                }
            } catch (RateLimitedException e) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
                problems.write(response, ErrorCode.RATE_LIMITED, e.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
