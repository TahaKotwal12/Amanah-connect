package com.amanahconnect.publicapi;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PingController {

    private final String version;
    private final Clock clock;

    public PingController(ObjectProvider<BuildProperties> buildProperties, Clock clock) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build == null ? "unknown" : build.getVersion();
        this.clock = clock;
    }

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse(version, Instant.now(clock));
    }
}
