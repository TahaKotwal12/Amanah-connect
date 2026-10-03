package com.amanahconnect.announcement;

import com.amanahconnect.announcement.AnnouncementDtos.CreatePlatformAnnouncementRequest;
import com.amanahconnect.announcement.AnnouncementDtos.PlatformAnnouncementView;
import com.amanahconnect.announcement.AnnouncementDtos.PlatformPreviewView;
import com.amanahconnect.announcement.AnnouncementDtos.ScheduleRequest;
import com.amanahconnect.announcement.AnnouncementDtos.TestSendView;
import com.amanahconnect.announcement.AnnouncementDtos.UpdatePlatformAnnouncementRequest;
import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/v1/admin/announcements")
@Tag(name = "Admin · Announcements", description = "Platform announcements and offers to community admins: email, in-app banner, or both (SUPER_ADMIN, 2FA completed).")
public class AdminAnnouncementController {

    private static final SortWhitelist SORT = SortWhitelist.of(Sort.by(Sort.Direction.DESC, "createdAt"), "createdAt", "updatedAt", "title", "status", "scheduledAt", "sentAt");

    private final PlatformAnnouncementService service;

    public AdminAnnouncementController(PlatformAnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List platform announcements")
    public PageResponse<PlatformAnnouncementView> list(@RequestParam(required = false) AnnouncementStatus status, @Valid PageQuery page) {
        return service.list(status, SORT.toPageRequest(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detail", description = "With the delivery counts and how many admins have read it.")
    public PlatformAnnouncementView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @AuditHandledBy("PlatformAnnouncementService records PLATFORM_ANNOUNCEMENT_CREATED")
    @Operation(summary = "Create", description = "kind ANNOUNCEMENT, OFFER or MAINTENANCE. communityIds empty means every active community. sendEmail emails each distinct active admin; banner also shows it in the app until expiresAt. HTML is sanitised like community announcements.")
    public ResponseEntity<PlatformAnnouncementView> create(@Valid @RequestBody CreatePlatformAnnouncementRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body));
    }

    @PatchMapping("/{id}")
    @AuditHandledBy("PlatformAnnouncementService records PLATFORM_ANNOUNCEMENT_UPDATED with before and after")
    @Operation(summary = "Edit a draft or scheduled announcement", description = "Partial. allCommunities=true clears the target list; clearExpiry=true removes the expiry; unschedule=true returns it to DRAFT. A sent one is a 409.")
    public PlatformAnnouncementView update(@PathVariable UUID id, @Valid @RequestBody UpdatePlatformAnnouncementRequest body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/schedule")
    @AuditHandledBy("PlatformAnnouncementService records PLATFORM_ANNOUNCEMENT_UPDATED")
    @Operation(summary = "Schedule for later")
    public PlatformAnnouncementView schedule(@PathVariable UUID id, @Valid @RequestBody ScheduleRequest body) {
        return service.update(id, new AnnouncementDtos.UpdatePlatformAnnouncementRequest(null, null, null, null, null, null, null, null, null, body.scheduledAt(), null));
    }

    @PostMapping("/{id}/send")
    @AuditHandledBy("PlatformAnnouncementService records PLATFORM_ANNOUNCEMENT_SENT with the delivery counts")
    @Operation(summary = "Send now", description = "A second call is a 409, never a second round of emails.")
    public PlatformAnnouncementView send(@PathVariable UUID id) {
        return service.send(id);
    }

    @GetMapping("/{id}/preview")
    @Operation(summary = "Preview", description = "The sanitised content and how many communities and admins it would reach. Queues nothing.")
    public PlatformPreviewView preview(@PathVariable UUID id) {
        return service.preview(id);
    }

    @PostMapping("/{id}/send-test")
    @AuditHandledBy("PlatformAnnouncementService records PLATFORM_ANNOUNCEMENT_TEST_SENT")
    @Operation(summary = "Send a test to me")
    public TestSendView sendTest(@PathVariable UUID id) {
        return service.sendTest(id);
    }
}
