package com.amanahconnect.billing;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.billing.BillingDtos.CreateFeePlanRequest;
import com.amanahconnect.billing.BillingDtos.FeePlanView;
import com.amanahconnect.billing.BillingDtos.UpdateFeePlanRequest;
import com.amanahconnect.tenant.CurrentCommunity;
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
@RequestMapping("/api/v1/community/fee-plans")
@Tag(name = "Community · Billing", description = "Fee plans, invoices, payments and receipts (COMMUNITY_ADMIN).")
public class FeePlanController {

    private final FeePlanService service;

    public FeePlanController(FeePlanService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List fee plans", description = "Optionally only the active (or only the inactive) ones.")
    public List<FeePlanView> list(@CurrentCommunity UUID communityId, @RequestParam(required = false) Boolean active) {
        return service.list(communityId, active);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fee plan detail")
    public FeePlanView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(communityId, id);
    }

    @PostMapping
    @AuditHandledBy("FeePlanService records FEE_PLAN_CREATED")
    @Operation(summary = "Create a fee plan", description = "Kind MAINTENANCE, SUBSCRIPTION, DONATION, EVENT, FINE or OTHER; frequency ONE_TIME, MONTHLY, QUARTERLY or YEARLY; applies to ALL_ACTIVE members, a GROUP or SELECTED members. autoGenerate (default off) bills every period from the next one on.")
    public ResponseEntity<FeePlanView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody CreateFeePlanRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(communityId, body));
    }

    @PatchMapping("/{id}")
    @AuditHandledBy("FeePlanService records FEE_PLAN_UPDATED with before and after")
    @Operation(summary = "Update a fee plan", description = "Partial. A new amount applies to future invoices only. Set active=false to retire a plan (plans are never deleted).")
    public FeePlanView update(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody UpdateFeePlanRequest body) {
        return service.update(communityId, id, body);
    }
}
