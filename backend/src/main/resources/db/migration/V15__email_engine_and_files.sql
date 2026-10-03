-- Email engine (sender worker, suppression list, reminders) and file accounting.

ALTER TABLE email_outbox
    ADD COLUMN last_attempt_at timestamptz;
COMMENT ON COLUMN email_outbox.next_attempt_at IS 'When the next attempt may start. The sender pushes it forward while it works on a row (a lease), so a crashed worker''s rows come back by themselves.';

-- Addresses the app must never email again: hard bounces and complaints reported by SES (via SNS), or added by hand.
CREATE TABLE email_suppressions
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    email      citext      NOT NULL,
    reason     varchar(20) NOT NULL,
    source     varchar(30) NOT NULL DEFAULT 'SES',
    detail     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_email_suppressions_email UNIQUE (email),
    CONSTRAINT ck_email_suppressions_reason CHECK (reason IN ('BOUNCE', 'COMPLAINT', 'MANUAL')),
    CONSTRAINT ck_email_suppressions_detail_object CHECK (jsonb_typeof(detail) = 'object')
);
CREATE TRIGGER trg_email_suppressions_updated_at BEFORE UPDATE ON email_suppressions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One row per reminder email queued for an invoice. The unique key makes the daily reminder job idempotent: running it
-- twice on the same day, or on two instances at once, queues each reminder once.
CREATE TABLE invoice_reminders
(
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id  uuid        NOT NULL REFERENCES communities (id),
    invoice_id    uuid        NOT NULL,
    kind          varchar(20) NOT NULL,
    reminder_date date        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_invoice_reminders_invoice FOREIGN KEY (invoice_id, community_id) REFERENCES invoices (id, community_id),
    CONSTRAINT uq_invoice_reminders UNIQUE (invoice_id, kind, reminder_date),
    CONSTRAINT ck_invoice_reminders_kind CHECK (kind IN ('DUE_SOON', 'OVERDUE'))
);
CREATE INDEX idx_invoice_reminders_community ON invoice_reminders (community_id);
CREATE INDEX idx_invoice_reminders_invoice ON invoice_reminders (invoice_id, kind, reminder_date DESC);
CREATE TRIGGER trg_invoice_reminders_updated_at BEFORE UPDATE ON invoice_reminders FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Every file a community has in object storage, so its usage can be counted against the plan's storage limit.
CREATE TABLE stored_files
(
    id           uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id uuid         NOT NULL REFERENCES communities (id),
    kind         varchar(30)  NOT NULL,
    object_key   varchar(255) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes   bigint       NOT NULL,
    created_by   uuid REFERENCES users (id),
    deleted_at   timestamptz,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_stored_files_key UNIQUE (object_key),
    CONSTRAINT ck_stored_files_kind CHECK (kind IN ('LOGO', 'LEDGER_ATTACHMENT', 'SUPPORT_ATTACHMENT', 'RECEIPT_PDF')),
    CONSTRAINT ck_stored_files_size CHECK (size_bytes > 0)
);
CREATE INDEX idx_stored_files_community ON stored_files (community_id) WHERE deleted_at IS NULL;
CREATE TRIGGER trg_stored_files_updated_at BEFORE UPDATE ON stored_files FOR EACH ROW EXECUTE FUNCTION set_updated_at();
