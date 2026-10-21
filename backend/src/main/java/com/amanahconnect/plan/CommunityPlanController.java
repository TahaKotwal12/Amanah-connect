package com.amanahconnect.plan;

import com.amanahconnect.plan.CommunityPlanService.CommunityPlanView;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/plan")
@Tag(name = "Community · Plan", description = "The community's own plan, limits, usage and subscription state (COMMUNITY_ADMIN).")
public class CommunityPlanController {

    private final CommunityPlanService service;

    public CommunityPlanController(CommunityPlanService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Plan, limits, usage and subscription",
            description = "Limits (null means unlimited), members and emails used, the community's status and the state of its latest paid period (ACTIVE, EXPIRING, EXPIRED), with days left. Readable while suspended.")
    public CommunityPlanView get(@CurrentCommunity UUID communityId) {
        return service.get(communityId);
    }
}
