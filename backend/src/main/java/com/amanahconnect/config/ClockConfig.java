package com.amanahconnect.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** All time-dependent code takes this clock so tests can pin time. Always UTC. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
