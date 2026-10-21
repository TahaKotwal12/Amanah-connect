package com.amanahconnect.reminder;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.common.page.PageQuery;
import com.amanahconnect.common.page.PageResponse;
import com.amanahconnect.reminder.OverdueRemindersService.DefaulterView;
import com.amanahconnect.reminder.OverdueRemindersService.RemindResult;
import com.amanahconnect.tenant.CurrentCommunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/community/reminders")
@Tag(name = "Community · Reminders", description = "Who is overdue, and sending the overdue notices now (COMMUNITY_ADMIN).")
public class ReminderController {

    private final OverdueRemindersService service;

    public ReminderController(OverdueRemindersService service) {
        this.service = service;
    }

    @GetMapping("/defaulters")
    @Operation(summary = "Members with overdue invoices", description = "One row per member who owes money past its due date: how many invoices, how much, the oldest due date and when they were last reminded. Largest debt first; the order is fixed.")
    public PageResponse<DefaulterView> defaulters(@CurrentCommunity UUID communityId, @Valid PageQuery page) {
        return service.defaulters(communityId, org.springframework.data.domain.PageRequest.of(page.pageOrDefault(), page.sizeOrDefault()));
    }

    @PostMapping("/overdue")
    @AuditHandledBy("OverdueRemindersService records OVERDUE_REMINDERS_SENT with the counts")
    @Operation(summary = "Send overdue notices now", description = "An overdue notice for every overdue invoice of an active member with an address who agreed to email, within the email quota. Sends each invoice's notice once a day however often it is called, and counts towards the daily sweep's spacing.")
    public RemindResult remindAll(@CurrentCommunity UUID communityId) {
        return service.remindAllOverdue(communityId);
    }
}
