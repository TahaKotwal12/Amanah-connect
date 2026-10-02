package com.amanahconnect.common.web;

import java.util.Map;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public health probes on the main port (for the load balancer and container health checks).
 *
 * <p>Actuator itself lives on the private management port (see {@code management.server.port}), so
 * the main port gets only these three status-only endpoints. They never include component details.
 */
@RestController
@RequestMapping("/actuator/health")
public class PublicHealthController {

    private final HealthEndpoint healthEndpoint;

    public PublicHealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        return toResponse(healthEndpoint.health());
    }

    @GetMapping("/liveness")
    public ResponseEntity<Map<String, String>> liveness() {
        return toResponse(healthEndpoint.healthForPath("liveness"));
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, String>> readiness() {
        return toResponse(healthEndpoint.healthForPath("readiness"));
    }

    private static ResponseEntity<Map<String, String>> toResponse(HealthDescriptor descriptor) {
        Status status = descriptor == null ? Status.UNKNOWN : descriptor.getStatus();
        HttpStatus http =
                Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(http).body(Map.of("status", status.getCode()));
    }
}
