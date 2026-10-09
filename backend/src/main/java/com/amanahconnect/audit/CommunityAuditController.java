package com.amanahconnect.audit;

import com.amanahconnect.audit.AuditDtos.CommunityEntry;
import com.amanahconnect.audit.AuditDtos.Filter;
import com.amanahconnect.audit.AuditDtos.Page;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/community/audit")
@Tag(name = "Community · Audit", description = "Your community's own audit trail, read-only (COMMUNITY_ADMIN).")
public class CommunityAuditController {

    private final AuditReadService service;

    public CommunityAuditController(AuditReadService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Your community's audit trail",
            description = "Who did what, newest first, with a plain-language summary. Only this community's entries; platform staff appear as \"Amanah Connect staff\"; no addresses or devices. "
                    + "Filters: actor (user id), action or actionPrefix (e.g. PAYMENT_), entityType, entityId, from and to (a date in India time or a UTC time). "
                    + "Pages by keyset: pass nextCursor back as cursor. limit is 1 to 200 (default 50).")
    public Page<CommunityEntry> trail(
            @CurrentCommunity UUID communityId,
            @RequestParam(required = false) UUID actor,
            @RequestParam(required = false) @Size(max = 100) String action,
            @RequestParam(required = false) @Size(max = 100) String actionPrefix,
            @RequestParam(required = false) @Size(max = 100) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) @Size(max = 40) String from,
            @RequestParam(required = false) @Size(max = 40) String to,
            @RequestParam(required = false) @Size(max = 200) String cursor,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit) {
        Filter filter = AuditFilters.of(actor, null, action, actionPrefix, entityType, entityId, from, to);
        return service.community(communityId, filter, cursor, limit);
    }
}
