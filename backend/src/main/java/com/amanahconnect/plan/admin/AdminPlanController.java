package com.amanahconnect.plan.admin;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.plan.admin.AdminPlanDtos.CreatePlanRequest;
import com.amanahconnect.plan.admin.AdminPlanDtos.PlanView;
import com.amanahconnect.plan.admin.AdminPlanDtos.UpdatePlanRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/plans")
@Tag(name = "Admin · Plans", description = "Plan catalogue: prices, limits and features (SUPER_ADMIN, 2FA completed).")
public class AdminPlanController {

    private final AdminPlanService service;

    public AdminPlanController(AdminPlanService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List plans", description = "All plans including inactive ones, optionally filtered by the active and public flags.")
    public List<PlanView> list(@RequestParam(required = false) Boolean active, @RequestParam(name = "public", required = false) Boolean publicPlan) {
        return service.list(active, publicPlan);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Plan detail")
    public PlanView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @AuditHandledBy("AdminPlanService records PLAN_CREATED")
    @Operation(summary = "Create a plan", description = "Limits and features are validated: unknown limit keys are rejected so a typo cannot silently mean unlimited.")
    public ResponseEntity<PlanView> create(@Valid @RequestBody CreatePlanRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @PatchMapping("/{id}")
    @AuditHandledBy("AdminPlanService records PLAN_UPDATED with before and after")
    @Operation(summary = "Update a plan", description = "Partial update. Changed limits apply to communities on the plan immediately.")
    public PlanView update(@PathVariable UUID id, @Valid @RequestBody UpdatePlanRequest body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/deactivate")
    @AuditHandledBy("AdminPlanService records PLAN_DEACTIVATED")
    @Operation(summary = "Deactivate", description = "Stops the plan being offered. Communities already on it are not affected.")
    public PlanView deactivate(@PathVariable UUID id) {
        return service.setActive(id, false);
    }

    @PostMapping("/{id}/activate")
    @AuditHandledBy("AdminPlanService records PLAN_ACTIVATED")
    @Operation(summary = "Activate")
    public PlanView activate(@PathVariable UUID id) {
        return service.setActive(id, true);
    }
}
