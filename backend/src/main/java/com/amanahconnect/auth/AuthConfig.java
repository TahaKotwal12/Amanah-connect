package com.amanahconnect.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AuthConfig {

    /** BCrypt, cost 12 by default (lowered only in the test profile, where speed matters more). */
    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }
}
