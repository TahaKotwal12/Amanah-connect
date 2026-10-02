-- Super admin module: community status reasons, platform subscription tracking, lead pipeline and
-- import batches (dry-run then confirm).

-- ---- communities: why and when the status last changed ---------------------------------------
ALTER TABLE communities
    ADD COLUMN status_reason     text,
    ADD COLUMN status_changed_at timestamptz,
    ADD COLUMN status_changed_by uuid REFERENCES users (id);

-- ---- platform subscriptions -------------------------------------------------------------------
-- paid_on: the day the community paid (revenue is reported by this date, not by the service period).
-- reminder_*: set when a reminder email was queued, so the daily job never repeats one.
ALTER TABLE platform_subscriptions
    ADD COLUMN paid_on             date NOT NULL DEFAULT current_date,
    ADD COLUMN cancel_reason       text,
    ADD COLUMN reminder_7d_sent_at timestamptz,
    ADD COLUMN reminder_1d_sent_at timestamptz,
    ADD COLUMN expired_notified_at timestamptz;

ALTER TABLE platform_subscriptions
    ADD CONSTRAINT ck_platform_subscriptions_cancel_reason
        CHECK (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0));

-- The same payment reference cannot be recorded twice for a community (guards double entry).
CREATE UNIQUE INDEX uq_platform_subscriptions_reference
    ON platform_subscriptions (community_id, lower(reference))
    WHERE reference IS NOT NULL AND status <> 'CANCELLED';

-- A platform payment is a financial row: cancel it (with a reason), never delete it.
CREATE TRIGGER trg_platform_subscriptions_no_delete
    BEFORE DELETE ON platform_subscriptions FOR EACH ROW EXECUTE FUNCTION forbid_delete();

-- ---- leads --------------------------------------------------------------------------------------
-- No lead could be created before this release (there was no endpoint), so this only renames values.
UPDATE leads SET status = 'LOST' WHERE status = 'CLOSED';
UPDATE leads SET status = 'CONTACTED' WHERE status = 'CONVERTED';

ALTER TABLE leads DROP CONSTRAINT ck_leads_status;
ALTER TABLE leads
    ADD COLUMN converted_community_id uuid REFERENCES communities (id);
ALTER TABLE leads
    ADD CONSTRAINT ck_leads_status CHECK (status IN ('NEW', 'CONTACTED', 'DEMO_SCHEDULED', 'CONVERTED', 'LOST')),
    ADD CONSTRAINT ck_leads_converted CHECK (status <> 'CONVERTED' OR converted_community_id IS NOT NULL);
CREATE INDEX idx_leads_converted_community ON leads (converted_community_id);

-- ---- import batches -----------------------------------------------------------------------------
-- One row per (community, client-supplied batch id). A dry run stores the validated rows; confirming
-- applies exactly those rows. Confirming twice returns the stored result, so a retry never duplicates.
CREATE TABLE import_batches
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id uuid        NOT NULL REFERENCES communities (id),
    batch_id     uuid        NOT NULL,
    payload_hash varchar(64) NOT NULL,
    status       varchar(20) NOT NULL DEFAULT 'DRY_RUN',
    report       jsonb       NOT NULL,
    rows         jsonb       NOT NULL,
    confirmable  boolean     NOT NULL,
    skip_invalid boolean,
    result       jsonb,
    created_by   uuid        NOT NULL REFERENCES users (id),
    confirmed_by uuid REFERENCES users (id),
    confirmed_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_import_batches_community_batch UNIQUE (community_id, batch_id),
    CONSTRAINT ck_import_batches_status CHECK (status IN ('DRY_RUN', 'CONFIRMED')),
    CONSTRAINT ck_import_batches_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_import_batches_confirmed CHECK (status <> 'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND result IS NOT NULL))
);
CREATE INDEX idx_import_batches_community ON import_batches (community_id);
CREATE INDEX idx_import_batches_community_status ON import_batches (community_id, status);
CREATE TRIGGER trg_import_batches_updated_at BEFORE UPDATE ON import_batches FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_import_batches_no_delete BEFORE DELETE ON import_batches FOR EACH ROW EXECUTE FUNCTION forbid_delete();
