package com.amanahconnect.report;

import com.amanahconnect.report.AdminStatsService.Monthly;
import com.amanahconnect.report.AdminStatsService.Stats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/stats")
@Tag(name = "Admin · Stats", description = "Platform dashboard numbers (SUPER_ADMIN, 2FA completed).")
public class AdminStatsController {

    private final AdminStatsService service;

    public AdminStatsController(AdminStatsService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Platform statistics", description = "Communities by status, total members, platform revenue this month and year (INR, strings), expiring subscriptions, new leads and open support threads.")
    public Stats stats() {
        return service.stats();
    }

    @GetMapping("/monthly")
    @Operation(summary = "Revenue and new communities by month", description = "The last N calendar months (default 12, at most 36), oldest first, months with nothing included as zero. Revenue is by paid date, cancelled payments excluded.")
    public Monthly monthly(@RequestParam(defaultValue = "12") int months) {
        return service.monthly(months);
    }
}
