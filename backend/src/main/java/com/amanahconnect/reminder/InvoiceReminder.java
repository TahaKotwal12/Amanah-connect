package com.amanahconnect.reminder;

import com.amanahconnect.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A reminder email queued for an invoice on a day. The unique (invoice, kind, day) key is what makes the reminder job idempotent. */
@Entity
@Table(name = "invoice_reminders")
@Getter
@Setter
public class InvoiceReminder extends TenantEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private ReminderKind kind;

    @Column(name = "reminder_date", nullable = false)
    private LocalDate reminderDate;
}
