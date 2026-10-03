-- Finance module for COMMUNITY_ADMIN: fee plans, invoices, payments, receipts, pay links and the ledger.
-- Existing rules stay: financial rows are never deleted, corrections are reversal rows.

-- ---- fee kinds -----------------------------------------------------------------------------------
-- MEMBERSHIP stays valid: invoices imported from an old system use it.
ALTER TABLE fee_plans DROP CONSTRAINT ck_fee_plans_kind;
ALTER TABLE fee_plans ADD CONSTRAINT ck_fee_plans_kind
    CHECK (kind IN ('MAINTENANCE', 'SUBSCRIPTION', 'MEMBERSHIP', 'DONATION', 'EVENT', 'FINE', 'OTHER'));
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_kind;
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_kind
    CHECK (kind IN ('MAINTENANCE', 'SUBSCRIPTION', 'MEMBERSHIP', 'DONATION', 'EVENT', 'FINE', 'OTHER'));

-- ---- fee plans -------------------------------------------------------------------------------------
-- Automatic billing is opt-in: a plan only bills on its own when auto_generate is set. last_generated_period
-- is the period the daily job already billed, so re-running the job never bills twice.
ALTER TABLE fee_plans
    ADD COLUMN auto_generate         boolean     NOT NULL DEFAULT false,
    ADD COLUMN last_generated_period varchar(30);

-- ---- invoices --------------------------------------------------------------------------------------
ALTER TABLE invoices
    ADD COLUMN issued_on     date        NOT NULL DEFAULT current_date,
    ADD COLUMN description   varchar(200),
    ADD COLUMN cancel_reason text,
    ADD COLUMN cancelled_at  timestamptz,
    ADD COLUMN cancelled_by  uuid REFERENCES users (id),
    ADD CONSTRAINT ck_invoices_cancel_reason CHECK (status <> 'CANCELLED' OR length(btrim(coalesce(cancel_reason, ''))) > 0),
    ADD CONSTRAINT ck_invoices_plan_period CHECK (fee_plan_id IS NULL OR period IS NOT NULL);

-- One invoice per fee plan, member and period (a cancelled one may be billed again). This is what makes
-- generating the same period twice harmless, even under concurrency.
CREATE UNIQUE INDEX uq_invoices_fee_plan_member_period
    ON invoices (community_id, fee_plan_id, member_id, period)
    WHERE fee_plan_id IS NOT NULL AND status <> 'CANCELLED';
CREATE INDEX idx_invoices_community_issued_on ON invoices (community_id, issued_on);
CREATE INDEX idx_invoices_overdue_scan ON invoices (due_date) WHERE status IN ('ISSUED', 'PARTIAL');

-- ---- payments -----------------------------------------------------------------------------------------
-- A donation from someone who is not a member has a donor name instead of a member.
ALTER TABLE payment_records ALTER COLUMN member_id DROP NOT NULL;
ALTER TABLE payment_records
    ADD COLUMN donor_name      varchar(150),
    ADD COLUMN idempotency_key varchar(100),
    ADD COLUMN request_hash    varchar(64),
    ADD CONSTRAINT ck_payment_records_payer CHECK (member_id IS NOT NULL OR donor_name IS NOT NULL),
    ADD CONSTRAINT ck_payment_records_idempotency CHECK (idempotency_key IS NULL OR request_hash IS NOT NULL);
-- The same Idempotency-Key can never record two payments in one community.
CREATE UNIQUE INDEX uq_payment_records_idempotency ON payment_records (community_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- ---- public pay links ---------------------------------------------------------------------------------
-- The raw token exists only in the bill email / the admin's copy; only its SHA-256 is stored.
CREATE TABLE payment_links
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id uuid        NOT NULL REFERENCES communities (id),
    invoice_id   uuid        NOT NULL,
    token_hash   varchar(64) NOT NULL,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    created_by   uuid REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_links_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_payment_links_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_payment_links_invoice FOREIGN KEY (invoice_id, community_id) REFERENCES invoices (id, community_id)
);
CREATE INDEX idx_payment_links_community ON payment_links (community_id);
CREATE INDEX idx_payment_links_invoice ON payment_links (invoice_id);
CREATE TRIGGER trg_payment_links_updated_at BEFORE UPDATE ON payment_links FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---- ledger ------------------------------------------------------------------------------------------------
-- Categories the system itself posts to (payments, donations) are found by system_key, not by name, so an
-- admin can rename them without breaking the automatic ledger entries.
ALTER TABLE ledger_category_templates ADD COLUMN system_key varchar(30);
ALTER TABLE ledger_categories ADD COLUMN system_key varchar(30);
UPDATE ledger_category_templates SET system_key = 'MEMBERSHIP_FEES' WHERE name = 'Membership Fees' AND type = 'INCOME';
UPDATE ledger_category_templates SET system_key = 'DONATIONS' WHERE name = 'Donations' AND type = 'INCOME';
UPDATE ledger_category_templates SET system_key = 'EVENTS' WHERE name = 'Events' AND type = 'INCOME';
UPDATE ledger_category_templates SET system_key = 'OTHER_INCOME' WHERE name = 'Other' AND type = 'INCOME';
UPDATE ledger_categories c SET system_key = t.system_key
FROM ledger_category_templates t
WHERE t.system_key IS NOT NULL AND c.name = t.name AND c.type = t.type;
CREATE UNIQUE INDEX uq_ledger_categories_system_key ON ledger_categories (community_id, system_key) WHERE system_key IS NOT NULL;

CREATE OR REPLACE FUNCTION init_community_defaults() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO ledger_categories (community_id, name, type, system_key)
    SELECT NEW.id, t.name, t.type, t.system_key
    FROM ledger_category_templates t
    ORDER BY t.sort_order;

    INSERT INTO notification_settings (community_id) VALUES (NEW.id);

    RETURN NEW;
END
$$;

-- ---- opening balance --------------------------------------------------------------------------------------
-- The balance the community had before its first ledger entry.
ALTER TABLE communities ADD COLUMN opening_balance numeric(14, 2) NOT NULL DEFAULT 0;
