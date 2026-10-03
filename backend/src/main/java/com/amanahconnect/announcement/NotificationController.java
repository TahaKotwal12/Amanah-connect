package com.amanahconnect.announcement;

import com.amanahconnect.announcement.AnnouncementDtos.NotificationSummary;
import com.amanahconnect.announcement.AnnouncementDtos.NotificationView;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/notifications")
@Tag(name = "Community · Notifications", description = "Platform announcements and offers for this community's admins, with banners and read state (COMMUNITY_ADMIN).")
public class NotificationController {

    private static final SortWhitelist SORT = SortWhitelist.of(Sort.by(Sort.Direction.DESC, "sentAt"), "sentAt");

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Notifications", description = "Newest first, with a read flag for the signed-in admin. unread=true shows only the unread ones.")
    public PageResponse<NotificationView> list(@CurrentCommunity UUID communityId, @RequestParam(required = false, defaultValue = "false") boolean unread, @Valid PageQuery page) {
        SORT.toPageRequest(page); // validates paging and sort; the order is fixed (newest first)
        return service.list(communityId, unread, org.springframework.data.domain.PageRequest.of(page.pageOrDefault(), page.sizeOrDefault()));
    }

    @GetMapping("/summary")
    @Operation(summary = "Badge and banners", description = "The number of unread notifications and the banners still to show. Cheap to poll.")
    public NotificationSummary summary(@CurrentCommunity UUID communityId) {
        return service.summary(communityId);
    }

    @PostMapping("/{id}/read")
    @AuditHandledBy("NotificationService records NOTIFICATION_READ, only when it was unread")
    @Operation(summary = "Mark one as read", description = "Also dismisses its banner for this admin. An announcement not addressed to this community is a 404.")
    public NotificationSummary markRead(@CurrentCommunity UUID communityId, @PathVariable UUID id) {
        return service.markRead(communityId, id);
    }

    @PostMapping("/read-all")
    @AuditHandledBy("NotificationService records NOTIFICATIONS_READ_ALL, only when something was unread")
    @Operation(summary = "Mark all as read")
    public NotificationSummary markAllRead(@CurrentCommunity UUID communityId) {
        return service.markAllRead(communityId);
    }
}
