package com.amanahconnect.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.amanahconnect.support.AbstractIntegrationTest;
import com.amanahconnect.support.tenant.CrossTenantCoverage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * "Every endpoint has a cross-tenant test" as a build rule. Lists every controller method mapped under
 * /api/v1/community and fails if one was never exercised through the {@code AbstractTenantIT}
 * assertions. Runs last (see junit-platform.properties) so every other test class has registered its
 * coverage first. The test-support endpoints are exempt.
 */
@Order(Integer.MAX_VALUE)
class TenantCoverageIT extends AbstractIntegrationTest {

    @Autowired RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyCommunityEndpointHasACrossTenantTest() {
        List<String> uncovered = new ArrayList<>();
        int inspected = 0;
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            List<String> patterns = info.getPathPatternsCondition() == null
                    ? List.of()
                    : info.getPathPatternsCondition().getPatterns().stream().map(p -> p.getPatternString()).toList();
            if (patterns.stream().noneMatch(p -> p.startsWith("/api/v1/community/")) || patterns.stream().anyMatch(p -> p.contains("/test-support/"))) {
                continue;
            }
            inspected++;
            String key = handler.getBeanType().getName() + "#" + handler.getMethod().getName();
            if (!CrossTenantCoverage.covered().contains(key)) {
                uncovered.add(info + "  ->  " + key);
            }
        }

        assertThat(uncovered)
                .as("/community endpoints (%d inspected) with no cross-tenant test. Extend AbstractTenantIT and call "
                        + "assertCrossTenantRead / Update / Delete, assertListHides or assertCreateCannotTargetOtherTenant.", inspected)
                .isEmpty();
    }
}
