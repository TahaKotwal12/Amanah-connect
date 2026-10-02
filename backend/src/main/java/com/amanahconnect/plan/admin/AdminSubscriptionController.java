package com.amanahconnect.plan.admin;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.plan.SubscriptionProperties;
import com.amanahconnect.plan.admin.AdminPlanDtos.CancelSubscriptionRequest;
import com.amanahconnect.plan.admin.AdminPlanDtos.RecordSubscriptionRequest;
import com.amanahconnect.plan.admin.AdminPlanDtos.SubscriptionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/admin/subscriptions")
@Tag(name = "Admin · Subscriptions", description = "Platform subscriptions: manual payments and expiry (SUPER_ADMIN, 2FA completed).")
public class AdminSubscriptionController {

    private static final SortWhitelist LIST_SORT =
            SortWhitelist.of(Sort.by(Sort.Direction.DESC, "paidOn"), Map.of("periodEnd", "periodEnd", "periodStart", "periodStart", "paidOn", "paidOn", "amount", "amount", "createdAt", "createdAt", "community", "communityName"));
    private static final SortWhitelist EXPIRING_SORT =
            SortWhitelist.of(Sort.by(Sort.Direction.ASC, "periodEnd"), Map.of("periodEnd", "periodEnd", "community", "communityName"));

    private final AdminSubscriptionService service;
    private final SubscriptionProperties properties;

    public AdminSubscriptionController(AdminSubscriptionService service, SubscriptionProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping
    @Operation(summary = "List subscriptions", description = "Filter by derived status (ACTIVE, EXPIRING, EXPIRED, CANCELLED) and community.")
    public PageResponse<SubscriptionView> list(
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|EXPIRING|EXPIRED|CANCELLED") String status,
            @RequestParam(required = false) UUID communityId,
            @Valid PageQuery page) {
        return service.list(status, communityId, properties.expiringWindowDays(), LIST_SORT.toPageRequest(page));
    }

    @GetMapping("/expiring")
    @Operation(summary = "Subscriptions expiring within N days", description = "Not yet renewed by a later subscription. Defaults to the configured window; soonest first.")
    public PageResponse<SubscriptionView> expiring(@RequestParam(required = false) @Min(1) @Max(365) Integer days, @Valid PageQuery page) {
        int window = days == null ? properties.expiringWindowDays() : days;
        return service.list("EXPIRING", null, window, EXPIRING_SORT.toPageRequest(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Subscription detail")
    public SubscriptionView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @AuditHandledBy("AdminSubscriptionService records SUBSCRIPTION_RECORDED")
    @Operation(summary = "Record a payment", description = "Records a manual payment for a period and moves the community to that plan if it is the latest subscription.")
    public ResponseEntity<SubscriptionView> record(@Valid @RequestBody RecordSubscriptionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.record(body));
    }

    @PostMapping("/{id}/cancel")
    @AuditHandledBy("AdminSubscriptionService records SUBSCRIPTION_CANCELLED")
    @Operation(summary = "Cancel a recorded payment", description = "Records are never deleted: a mistaken one is cancelled with a reason.")
    public SubscriptionView cancel(@PathVariable UUID id, @Valid @RequestBody CancelSubscriptionRequest body) {
        return service.cancel(id, body.reason());
    }
}
