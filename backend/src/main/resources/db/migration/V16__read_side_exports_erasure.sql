-- Dashboard and audit read paths (indexes), community data exports, and member erasure markers.

-- ---- indexes for the dashboard and the audit trail -----------------------------------------------------------------------
-- Keyset pagination orders by (created_at, id) descending, so every filter that is combined with it gets an index that ends the same way.
CREATE INDEX idx_audit_logs_created_id ON audit_logs (created_at DESC, id DESC);
CREATE INDEX idx_audit_logs_community_created_id ON audit_logs (community_id, created_at DESC, id DESC);
CREATE INDEX idx_audit_logs_actor_created_id ON audit_logs (actor_user_id, created_at DESC, id DESC);
CREATE INDEX idx_audit_logs_action_created_id ON audit_logs (action, created_at DESC, id DESC);
CREATE INDEX idx_audit_logs_entity_created_id ON audit_logs (entity_type, entity_id, created_at DESC, id DESC);

-- "Billed this month / the last 12 months" and "what falls due soon" only ever look at invoices that count.
CREATE INDEX idx_invoices_community_open_due ON invoices (community_id, due_date) WHERE status IN ('ISSUED', 'PARTIAL', 'OVERDUE');

-- ---- member erasure -------------------------------------------------------------------------------------------------------
ALTER TABLE members
    ADD COLUMN anonymised_at timestamptz,
    ADD COLUMN anonymised_by uuid REFERENCES users (id);
ALTER TABLE members
    ADD CONSTRAINT ck_members_anonymised CHECK (anonymised_at IS NULL OR (email IS NULL AND phone IS NULL AND group_label IS NULL AND consent_email = false));
ALTER TABLE member_registrations
    ADD COLUMN anonymised_at timestamptz;

-- ---- community data export -----------------------------------------------------------------------------------------------
-- One row per export request. A worker builds the ZIP, stores it in S3 and emails the requester a link that works until link_expires_at.
-- Only the hash of the download token is kept.
CREATE TABLE data_exports
(
    id               uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id     uuid        NOT NULL REFERENCES communities (id),
    requested_by     uuid        NOT NULL REFERENCES users (id),
    status           varchar(20) NOT NULL DEFAULT 'PENDING',
    object_key       varchar(255),
    size_bytes       bigint,
    tables           jsonb       NOT NULL DEFAULT '{}'::jsonb, -- table name -> row count
    token_hash       varchar(64),
    link_expires_at  timestamptz,
    download_count   integer     NOT NULL DEFAULT 0,
    error            text,
    started_at       timestamptz,
    completed_at     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_data_exports_status CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_data_exports_tables_object CHECK (jsonb_typeof(tables) = 'object'),
    CONSTRAINT ck_data_exports_token_hash CHECK (token_hash IS NULL OR token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_data_exports_ready CHECK (status <> 'READY' OR (object_key IS NOT NULL AND size_bytes IS NOT NULL AND token_hash IS NOT NULL AND link_expires_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_data_exports_token_hash ON data_exports (token_hash) WHERE token_hash IS NOT NULL;
CREATE INDEX idx_data_exports_community_created ON data_exports (community_id, created_at DESC);
CREATE INDEX idx_data_exports_due ON data_exports (status, created_at) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_data_exports_expiring ON data_exports (link_expires_at) WHERE status = 'READY';
CREATE TRIGGER trg_data_exports_updated_at BEFORE UPDATE ON data_exports FOR EACH ROW EXECUTE FUNCTION set_updated_at();
