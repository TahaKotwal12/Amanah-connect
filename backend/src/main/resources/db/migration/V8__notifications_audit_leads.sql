-- Email outbox, notification settings, append-only audit log, leads and the ShedLock table.

CREATE TABLE email_outbox
(
    id              uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id    uuid REFERENCES communities (id), -- null for platform emails (password reset, invites)
    to_email        citext       NOT NULL,
    template        varchar(100) NOT NULL,
    payload         jsonb        NOT NULL DEFAULT '{}'::jsonb,
    status          varchar(10)  NOT NULL DEFAULT 'PENDING',
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    ses_message_id  varchar(100),
    error           text,
    sent_at         timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_email_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_email_outbox_payload_object CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX idx_email_outbox_community ON email_outbox (community_id);
CREATE INDEX idx_email_outbox_community_status ON email_outbox (community_id, status);
CREATE INDEX idx_email_outbox_to_email ON email_outbox (to_email);
CREATE INDEX idx_email_outbox_due ON email_outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE TRIGGER trg_email_outbox_updated_at BEFORE UPDATE ON email_outbox FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE notification_settings
(
    id                          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id                uuid        NOT NULL REFERENCES communities (id),
    due_reminder_days_before    integer     NOT NULL DEFAULT 3,
    overdue_reminder_every_days integer     NOT NULL DEFAULT 7,
    send_welcome                boolean     NOT NULL DEFAULT true,
    send_receipt                boolean     NOT NULL DEFAULT true,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_settings_community UNIQUE (community_id),
    CONSTRAINT ck_notification_settings_due_days CHECK (due_reminder_days_before BETWEEN 0 AND 60),
    CONSTRAINT ck_notification_settings_overdue_days CHECK (overdue_reminder_every_days BETWEEN 1 AND 90)
);
CREATE TRIGGER trg_notification_settings_updated_at BEFORE UPDATE ON notification_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Append-only. There is deliberately no updated_at.
CREATE TABLE audit_logs
(
    id            uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    actor_user_id uuid REFERENCES users (id),
    community_id  uuid REFERENCES communities (id),
    action        varchar(100) NOT NULL,
    entity_type   varchar(100),
    entity_id     uuid,
    before        jsonb,
    after         jsonb,
    ip            varchar(45),
    user_agent    varchar(512),
    request_id    varchar(64),
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_community ON audit_logs (community_id);
CREATE INDEX idx_audit_logs_community_created ON audit_logs (community_id, created_at DESC);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_user_id);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_created ON audit_logs (created_at DESC);

CREATE FUNCTION audit_logs_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END
$$;
CREATE TRIGGER trg_audit_logs_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION audit_logs_append_only();
CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs FOR EACH STATEMENT EXECUTE FUNCTION audit_logs_append_only();

CREATE TABLE leads
(
    id             uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    name           varchar(150) NOT NULL,
    email          citext       NOT NULL,
    phone          varchar(30),
    community_name varchar(200),
    size_estimate  integer,
    message        text,
    status         varchar(20)  NOT NULL DEFAULT 'NEW',
    handled_by     uuid REFERENCES users (id),
    source         varchar(50)  NOT NULL DEFAULT 'WEBSITE',
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_leads_status CHECK (status IN ('NEW', 'CONTACTED', 'CONVERTED', 'CLOSED')),
    CONSTRAINT ck_leads_size_estimate CHECK (size_estimate IS NULL OR size_estimate >= 0)
);
CREATE INDEX idx_leads_email ON leads (email);
CREATE INDEX idx_leads_status ON leads (status);
CREATE INDEX idx_leads_created ON leads (created_at DESC);
CREATE TRIGGER trg_leads_updated_at BEFORE UPDATE ON leads FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Standard ShedLock schema (scheduled-job locks). Used from the notification/scheduling work.
CREATE TABLE shedlock
(
    name       varchar(64)  NOT NULL PRIMARY KEY,
    lock_until timestamp(3) NOT NULL,
    locked_at  timestamp(3) NOT NULL,
    locked_by  varchar(255) NOT NULL
);
