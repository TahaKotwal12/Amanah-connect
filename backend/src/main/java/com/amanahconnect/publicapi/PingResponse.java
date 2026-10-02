package com.amanahconnect.publicapi;

import java.time.Instant;

public record PingResponse(String version, Instant time) {}
