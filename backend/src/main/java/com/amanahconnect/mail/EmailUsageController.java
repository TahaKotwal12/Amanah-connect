package com.amanahconnect.mail;

import com.amanahconnect.plan.PlanLimitService;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/email-usage")
@Tag(name = "Community · Email usage", description = "How much of the plan's email allowance the community has used (COMMUNITY_ADMIN).")
public class EmailUsageController {

    private final PlanLimitService planLimits;

    public EmailUsageController(PlanLimitService planLimits) {
        this.planLimits = planLimits;
    }

    @GetMapping
    @Operation(summary = "Email usage", description = "Emails queued today and this month (India calendar, failed ones not counted) against the plan's limits; a null limit means unlimited.")
    public PlanLimitService.EmailUsage usage(@CurrentCommunity UUID communityId) {
        return planLimits.emailUsage(communityId);
    }
}
