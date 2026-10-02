package com.amanahconnect.auth.web;

import com.amanahconnect.auth.AuthProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token cookie: HttpOnly (scripts cannot read it), Secure, SameSite=Strict (never sent
 * on cross-site requests) and scoped to the auth endpoints only.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "amanah_refresh";
    public static final String PATH = "/api/v1/auth";

    private final AuthProperties properties;

    public RefreshCookie(AuthProperties properties) {
        this.properties = properties;
    }

    public String issue(String rawToken) {
        return build(rawToken, properties.refreshTtl()).toString();
    }

    public String clear() {
        return build("", Duration.ZERO).toString();
    }

    private static ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
