package com.amanahconnect.config;

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
            CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                // Stateless API: requests are authenticated by a bearer token, never by a session
                // cookie, so there is no ambient credential for CSRF to abuse.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .headers(
                        headers ->
                                headers.contentTypeOptions(Customizer.withDefaults())
                                        .frameOptions(frame -> frame.deny())
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
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                (request, response, e) ->
                                                        problems.write(
                                                                response,
                                                                ErrorCode.UNAUTHENTICATED,
                                                                "Authentication is required."))
                                        .accessDeniedHandler(
                                                (request, response, e) ->
                                                        problems.write(
                                                                response,
                                                                ErrorCode.FORBIDDEN,
                                                                "You do not have access to this resource.")));
        return http.build();
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
