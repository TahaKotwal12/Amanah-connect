package com.amanahconnect.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.tenant.CurrentTenant;
import com.amanahconnect.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class LogContextFilterTest {

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private record Seen(String user, String community) {}

    private Seen run() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        new LogContextFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(new Seen(MDC.get(LogContextFilter.USER_KEY), MDC.get(LogContextFilter.COMMUNITY_KEY)));
            }
        });
        return seen.get();
    }

    @Test
    void anAuthenticatedRequestCarriesAHashedUserAndTheCommunity() throws Exception {
        UUID user = UUID.randomUUID();
        UUID community = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(user.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        Seen seen = TenantContext.callAs(new CurrentTenant(community, user, "ACTIVE", true), () -> {
            try {
                return run();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(seen.user()).hasSize(12).isEqualTo(LogContextFilter.hash(user.toString())).doesNotContain(user.toString().substring(0, 8));
        assertThat(seen.community()).isEqualTo(community.toString());
        assertThat(MDC.get(LogContextFilter.USER_KEY)).as("cleaned up after the request").isNull();
        assertThat(MDC.get(LogContextFilter.COMMUNITY_KEY)).isNull();
    }

    @Test
    void anAnonymousRequestAddsNothing() throws Exception {
        Seen seen = run();

        assertThat(seen.user()).isNull();
        assertThat(seen.community()).isNull();
    }
}
