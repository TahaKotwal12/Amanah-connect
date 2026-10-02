package com.amanahconnect.config;

import com.amanahconnect.auth.JwtService;
import com.amanahconnect.common.error.ErrorCode;
import com.amanahconnect.common.error.ProblemResponseWriter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny-by-default HTTP security. Only the endpoints listed below are public; everything else needs
 * an authenticated principal (authentication itself arrives with the auth module).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Requests arriving on the management port. That port is bound to the internal network only
     * (never published or proxied), so network isolation is the control there; it is not given
     * the application's authentication rules.
     */
    @Bean
    @Order(1)
    SecurityFilterChain managementChain(HttpSecurity http, Environment environment)
            throws Exception {
        // The bound port is only known at runtime (it is random when configured as 0), and Boot
        // publishes it as local.management.port once the management server is up.
        http.securityMatcher(
                        request -> {
                            Integer managementPort =
                                    environment.getProperty("local.management.port", Integer.class);
                            return managementPort != null
                                    && managementPort > 0
                                    && request.getLocalPort() == managementPort;
                        })
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    /** Swagger UI and the OpenAPI document, only registered when the local profile is active. */
    @Bean
    @Order(2)
    @Profile("local")
    SecurityFilterChain apiDocsChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain apiChain(
            HttpSecurity http,
            ProblemResponseWriter problems,
            CorsConfigurationSource corsConfigurationSource,
            JwtService jwtService)
            throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                // Stateless API. Bearer-authenticated endpoints have no ambient credential for CSRF to
                // abuse. The two endpoints that DO use a cookie (refresh, logout) are protected by
                // CookieEndpointGuardFilter (custom header + Origin check) and a SameSite=Strict cookie.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .headers(
                        headers ->
                                headers.contentTypeOptions(Customizer.withDefaults())
                                        .frameOptions(frame -> frame.deny())
                                        .httpStrictTransportSecurity(
                                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                                        .referrerPolicy(
                                                r ->
                                                        r.policy(
                                                                ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .NO_REFERRER))
                                        .contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'none'; frame-ancestors 'none'")))
                .oauth2ResourceServer(
                        oauth ->
                                oauth.bearerTokenResolver(publicAwareBearerTokenResolver())
                                        .jwt(
                                                jwt ->
                                                        jwt.decoder(jwtService.accessTokenDecoder())
                                                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                                        .authenticationEntryPoint(
                                                (request, response, e) -> unauthenticated(problems, response))
                                        .accessDeniedHandler((request, response, e) -> forbidden(problems, response)))
                .addFilterAfter(new MfaSetupEnforcementFilter(problems), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.GET, "/api/v1/ping")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/actuator/health",
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness")
                                        .permitAll()
                                        .requestMatchers("/api/v1/public/**")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/login/2fa",
                                                "/api/v1/auth/refresh",
                                                "/api/v1/auth/logout",
                                                "/api/v1/auth/password/forgot",
                                                "/api/v1/auth/password/reset",
                                                "/api/v1/auth/accept-invite")
                                        .permitAll()
                                        .requestMatchers("/api/v1/admin/**")
                                        .hasRole("SUPER_ADMIN")
                                        .requestMatchers("/api/v1/community/**")
                                        .hasRole("COMMUNITY_ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint((request, response, e) -> unauthenticated(problems, response))
                                        .accessDeniedHandler((request, response, e) -> forbidden(problems, response)));
        return http.build();
    }

    private static void unauthenticated(ProblemResponseWriter problems, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problems.write(response, ErrorCode.UNAUTHENTICATED, "Authentication is required.");
    }

    private static void forbidden(ProblemResponseWriter problems, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        problems.write(response, ErrorCode.FORBIDDEN, "You do not have access to this resource.");
    }

    /**
     * Ignores the Authorization header on public endpoints, so a stale or expired token left in a
     * client does not turn a login or health request into a 401.
     */
    private static BearerTokenResolver publicAwareBearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> PublicEndpoints.matches(request) ? null : delegate.resolve(request);
    }

    /** Maps the {@code role} claim to a ROLE_* authority, so hasRole("SUPER_ADMIN") works. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtService.CLAIM_ROLE);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** Strict CORS: only the configured app origins, none by default. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of(
                        HttpHeaders.AUTHORIZATION,
                        HttpHeaders.CONTENT_TYPE,
                        "X-Request-Id",
                        "X-Requested-With"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
