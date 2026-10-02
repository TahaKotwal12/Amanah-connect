package com.amanahconnect.notification;

import com.amanahconnect.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** One row per community; created automatically with the community by a database trigger. */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
public class NotificationSettings extends BaseEntity {

    @Column(name = "community_id", nullable = false, updatable = false)
    private UUID communityId;

    @Column(name = "due_reminder_days_before", nullable = false)
    private int dueReminderDaysBefore = 3;

    @Column(name = "overdue_reminder_every_days", nullable = false)
    private int overdueReminderEveryDays = 7;

    @Column(name = "send_welcome", nullable = false)
    private boolean sendWelcome = true;

    @Column(name = "send_receipt", nullable = false)
    private boolean sendReceipt = true;
}
