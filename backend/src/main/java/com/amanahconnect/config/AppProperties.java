package com.amanahconnect.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application-specific settings, bound from the {@code app.*} namespace. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors) {

    public AppProperties {
        cors = cors == null ? new Cors(List.of()) : cors;
    }

    /** Origins allowed to call the API from a browser. Empty means no cross-origin access. */
    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }
}
