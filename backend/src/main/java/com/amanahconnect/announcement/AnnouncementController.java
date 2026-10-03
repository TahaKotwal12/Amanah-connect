package com.amanahconnect.announcement;

import com.amanahconnect.announcement.AnnouncementDtos.AnnouncementView;
import com.amanahconnect.announcement.AnnouncementDtos.CreateAnnouncementRequest;
import com.amanahconnect.announcement.AnnouncementDtos.PreviewView;
import com.amanahconnect.announcement.AnnouncementDtos.ScheduleRequest;
import com.amanahconnect.announcement.AnnouncementDtos.TestSendView;
import com.amanahconnect.announcement.AnnouncementDtos.UpdateAnnouncementRequest;
import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.common.page.SortWhitelist;
import com.amanahconnect.tenant.CurrentCommunity;
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
@RequestMapping("/api/v1/community/announcements")
@Tag(name = "Community · Announcements", description = "Announcements to members: draft, preview, schedule, send (COMMUNITY_ADMIN).")
public class AnnouncementController {

    private static final SortWhitelist SORT = SortWhitelist.of(Sort.by(Sort.Direction.DESC, "createdAt"), "createdAt", "updatedAt", "title", "status", "scheduledAt", "sentAt");

    private final AnnouncementService service;

    public AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List announcements", description = "Optionally only DRAFT, SCHEDULED or SENT ones. Sort by createdAt (default, newest first), updatedAt, title, status, scheduledAt or sentAt.")
    public PageResponse<AnnouncementView> list(@CurrentCommunity UUID communityId, @RequestParam(required = false) AnnouncementStatus status, @Valid PageQuery page) {
        return service.list(communityId, status, SORT.toPageRequest(page));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Announcement detail", description = "Includes the delivery counts once sent: recipients, emails queued, and members skipped for having no address, no consent or no quota left.")
    public AnnouncementView get(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.get(communityId, id);
    }

    @PostMapping
    @AuditHandledBy("AnnouncementService records ANNOUNCEMENT_CREATED")
    @Operation(summary = "Create an announcement",
            description = "body is HTML and is sanitised against an allow-list (basic formatting, lists, headings, quotes, links; no scripts, images, forms, styles or event handlers). audience is ALL_ACTIVE (default), GROUP (with group) or SELECTED (with memberIds). "
                    + "A scheduledAt in the future creates it SCHEDULED, otherwise DRAFT. Nothing is emailed until it is sent.")
    public ResponseEntity<AnnouncementView> create(@CurrentCommunity UUID communityId, @Valid @RequestBody CreateAnnouncementRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(communityId, body));
    }

    @PatchMapping("/{id}")
    @AuditHandledBy("AnnouncementService records ANNOUNCEMENT_UPDATED with before and after")
    @Operation(summary = "Edit a draft or scheduled announcement", description = "Partial. A sent announcement answers 409 ANNOUNCEMENT_NOT_EDITABLE. unschedule=true returns it to DRAFT.")
    public AnnouncementView update(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody UpdateAnnouncementRequest body) {
        return service.update(communityId, id, body);
    }

    @PostMapping("/{id}/schedule")
    @AuditHandledBy("AnnouncementService records ANNOUNCEMENT_SCHEDULED")
    @Operation(summary = "Schedule for later", description = "scheduledAt must be in the future. It goes out within a minute of that time.")
    public AnnouncementView schedule(@CurrentCommunity UUID communityId, @PathVariable UUID id, @Valid @RequestBody ScheduleRequest body) {
        return service.schedule(communityId, id, body.scheduledAt());
    }

    @PostMapping("/{id}/unschedule")
    @AuditHandledBy("AnnouncementService records ANNOUNCEMENT_UNSCHEDULED")
    @Operation(summary = "Take it off the schedule", description = "Back to DRAFT.")
    public AnnouncementView unschedule(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.unschedule(communityId, id);
    }

    @PostMapping("/{id}/send")
    @AuditHandledBy("AnnouncementService records ANNOUNCEMENT_SENT with the delivery counts")
    @Operation(summary = "Send now",
            description = "Queues one email per active member who has an address and agreed to email (when sendEmail is on), up to what the plan's monthly email quota still allows; the rest are counted as skipped. "
                    + "Sending twice is a 409 ANNOUNCEMENT_STATE, never a second round of emails.")
    public AnnouncementView send(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.send(communityId, id);
    }

    @GetMapping("/{id}/preview")
    @Operation(summary = "Preview", description = "The sanitised content as it will be sent, who it would reach, who would be skipped and why, and the quota left. Queues nothing.")
    public PreviewView preview(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.preview(communityId, id);
    }

    @PostMapping("/{id}/send-test")
    @AuditHandledBy("AnnouncementService records ANNOUNCEMENT_TEST_SENT")
    @Operation(summary = "Send a test to me", description = "One email to the signed-in admin's own address (uses one email of the quota). Works on a draft.")
    public TestSendView sendTest(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.sendTest(communityId, id);
    }
}
