package com.amanahconnect.dashboard;

import com.amanahconnect.dashboard.DashboardDtos.Dashboard;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/dashboard")
@Tag(name = "Community · Dashboard", description = "Everything an admin needs on one screen (COMMUNITY_ADMIN).")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Dashboard",
            description = "Members (total, active); this month's collection (billed, collected, outstanding, and cash received in the month); overdue invoices (count and amount); the net balance "
                    + "(opening balance plus ledger income minus expense); open complaints and how many are past their SLA; unread support messages; upcoming dues (the next 14 days: total and the soonest ten); "
                    + "the last 10 things done in the community in plain language; and the last 12 months of billed vs received. Months are India calendar months. The answer is reused for up to 30 seconds.")
    public ResponseEntity<Dashboard> dashboard(@CurrentCommunity UUID communityId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(communityId));
    }
}
