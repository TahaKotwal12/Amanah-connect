package com.amanahconnect.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Every controller method mapped under /api/v1/admin, read from the running application. */
public final class AdminEndpoints {

    public record Endpoint(String method, String pattern, String handler) {

        /** The pattern with every path variable replaced by a random UUID. */
        public String concretePath() {
            return pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    private AdminEndpoints() {}

    public static List<Endpoint> all(RequestMappingHandlerMapping mapping) {
        List<Endpoint> endpoints = new ArrayList<>();
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (var pattern : info.getPathPatternsCondition().getPatterns()) {
                if (!pattern.getPatternString().startsWith("/api/v1/admin")) {
                    continue;
                }
                for (var method : info.getMethodsCondition().getMethods()) {
                    endpoints.add(new Endpoint(method.name(), pattern.getPatternString(), handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()));
                }
            }
        }
        endpoints.sort(java.util.Comparator.comparing(Endpoint::pattern).thenComparing(Endpoint::method));
        return endpoints;
    }
}
