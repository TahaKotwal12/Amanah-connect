-- Billing: fee plans, invoices, payment records, receipts and gap-free document counters.
-- Money is NUMERIC(14,2). Financial rows are never deleted: corrections are reversal rows with a
-- negative amount, a reference to the original (reversed_of) and a reason.

-- Shared guard: financial tables refuse DELETE at the database level.
CREATE FUNCTION forbid_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Rows in % are never deleted; record a reversal or cancel instead', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END
$$;

-- Counter rows are locked with SELECT ... FOR UPDATE inside the transaction that issues the
-- document, so numbers are gap-free (a rolled-back document returns its number) and unique.
CREATE TABLE document_counters
(
    id             uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id   uuid        NOT NULL REFERENCES communities (id),
    counter_type   varchar(20) NOT NULL,
    financial_year varchar(9)  NOT NULL, -- e.g. '2026-27', or '2026' for a calendar-year community
    last_value     bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_counters_scope UNIQUE (community_id, counter_type, financial_year),
    CONSTRAINT ck_document_counters_counter_type CHECK (counter_type IN ('INVOICE', 'RECEIPT')),
    CONSTRAINT ck_document_counters_last_value CHECK (last_value >= 0)
);
CREATE INDEX idx_document_counters_community ON document_counters (community_id);
CREATE TRIGGER trg_document_counters_updated_at BEFORE UPDATE ON document_counters FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_document_counters_no_delete BEFORE DELETE ON document_counters FOR EACH ROW EXECUTE FUNCTION forbid_delete();

CREATE TABLE fee_plans
(
    id               uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id     uuid           NOT NULL REFERENCES communities (id),
    name             varchar(150)   NOT NULL,
    kind             varchar(20)    NOT NULL,
    amount           numeric(14, 2) NOT NULL,
    frequency        varchar(20)    NOT NULL,
    due_day          smallint,
    applies_to       varchar(20)    NOT NULL DEFAULT 'ALL_ACTIVE',
    applies_to_filter jsonb         NOT NULL DEFAULT '{}'::jsonb, -- {"group": "..."} or {"memberIds": ["..."]}
    active           boolean        NOT NULL DEFAULT true,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_fee_plans_id_community UNIQUE (id, community_id),
    CONSTRAINT ck_fee_plans_kind CHECK (kind IN ('MEMBERSHIP', 'DONATION', 'EVENT', 'OTHER')),
    CONSTRAINT ck_fee_plans_amount CHECK (amount > 0),
    CONSTRAINT ck_fee_plans_frequency CHECK (frequency IN ('ONE_TIME', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    CONSTRAINT ck_fee_plans_due_day CHECK (due_day IS NULL OR due_day BETWEEN 1 AND 28),
    CONSTRAINT ck_fee_plans_applies_to CHECK (applies_to IN ('ALL_ACTIVE', 'GROUP', 'SELECTED')),
    CONSTRAINT ck_fee_plans_filter_object CHECK (jsonb_typeof(applies_to_filter) = 'object')
);
CREATE INDEX idx_fee_plans_community ON fee_plans (community_id);
CREATE INDEX idx_fee_plans_community_active ON fee_plans (community_id, active);
CREATE TRIGGER trg_fee_plans_updated_at BEFORE UPDATE ON fee_plans FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE invoices
(
    id           uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id uuid           NOT NULL REFERENCES communities (id),
    member_id    uuid           NOT NULL,
    invoice_no   varchar(40), -- assigned when the invoice is issued (drafts have none)
    kind         varchar(20)    NOT NULL,
    period       varchar(30),
    amount       numeric(14, 2) NOT NULL,
    amount_paid  numeric(14, 2) NOT NULL DEFAULT 0,
    due_date     date           NOT NULL,
    status       varchar(20)    NOT NULL DEFAULT 'DRAFT',
    fee_plan_id  uuid,
    version      bigint         NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_invoices_community_invoice_no UNIQUE (community_id, invoice_no),
    CONSTRAINT uq_invoices_id_community UNIQUE (id, community_id),
    CONSTRAINT fk_invoices_member FOREIGN KEY (member_id, community_id) REFERENCES members (id, community_id),
    CONSTRAINT fk_invoices_fee_plan FOREIGN KEY (fee_plan_id, community_id) REFERENCES fee_plans (id, community_id),
    CONSTRAINT ck_invoices_kind CHECK (kind IN ('MEMBERSHIP', 'DONATION', 'EVENT', 'OTHER')),
    CONSTRAINT ck_invoices_status CHECK (status IN ('DRAFT', 'ISSUED', 'PARTIAL', 'PAID', 'OVERDUE', 'CANCELLED')),
    CONSTRAINT ck_invoices_amount CHECK (amount > 0),
    CONSTRAINT ck_invoices_amount_paid CHECK (amount_paid >= 0 AND amount_paid <= amount),
    CONSTRAINT ck_invoices_paid_in_full CHECK (status <> 'PAID' OR amount_paid = amount),
    CONSTRAINT ck_invoices_draft_unpaid CHECK (status <> 'DRAFT' OR amount_paid = 0),
    CONSTRAINT ck_invoices_number_when_issued CHECK (status IN ('DRAFT', 'CANCELLED') OR invoice_no IS NOT NULL)
);
CREATE INDEX idx_invoices_community ON invoices (community_id);
CREATE INDEX idx_invoices_community_status ON invoices (community_id, status);
CREATE INDEX idx_invoices_community_due_date ON invoices (community_id, due_date);
CREATE INDEX idx_invoices_community_member ON invoices (community_id, member_id);
CREATE INDEX idx_invoices_fee_plan ON invoices (fee_plan_id);
CREATE TRIGGER trg_invoices_updated_at BEFORE UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_invoices_no_delete BEFORE DELETE ON invoices FOR EACH ROW EXECUTE FUNCTION forbid_delete();

CREATE TABLE payment_records
(
    id              uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id    uuid           NOT NULL REFERENCES communities (id),
    invoice_id      uuid, -- null for a payment that is not tied to an invoice
    member_id       uuid           NOT NULL,
    amount          numeric(14, 2) NOT NULL, -- positive payment, or negative when reversed_of is set
    method          varchar(20)    NOT NULL,
    reference       varchar(100),
    received_on     date           NOT NULL,
    recorded_by     uuid           NOT NULL REFERENCES users (id),
    receipt_id      uuid,
    reversed_of     uuid,
    reversal_reason text,
    version         bigint         NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_records_id_community UNIQUE (id, community_id),
    CONSTRAINT fk_payment_records_invoice FOREIGN KEY (invoice_id, community_id) REFERENCES invoices (id, community_id),
    CONSTRAINT fk_payment_records_member FOREIGN KEY (member_id, community_id) REFERENCES members (id, community_id),
    CONSTRAINT fk_payment_records_reversed_of FOREIGN KEY (reversed_of, community_id) REFERENCES payment_records (id, community_id),
    CONSTRAINT ck_payment_records_method CHECK (method IN ('CASH', 'UPI', 'BANK', 'CHEQUE', 'OTHER')),
    CONSTRAINT ck_payment_records_amount_sign CHECK (
        (reversed_of IS NULL AND amount > 0) OR (reversed_of IS NOT NULL AND amount < 0)),
    CONSTRAINT ck_payment_records_reversal_reason CHECK (
        reversed_of IS NULL OR length(btrim(coalesce(reversal_reason, ''))) > 0)
);
CREATE INDEX idx_payment_records_community ON payment_records (community_id);
CREATE INDEX idx_payment_records_community_received_on ON payment_records (community_id, received_on);
CREATE INDEX idx_payment_records_community_invoice ON payment_records (community_id, invoice_id);
CREATE INDEX idx_payment_records_community_member ON payment_records (community_id, member_id);
CREATE UNIQUE INDEX uq_payment_records_reversed_of ON payment_records (reversed_of) WHERE reversed_of IS NOT NULL;
CREATE TRIGGER trg_payment_records_updated_at BEFORE UPDATE ON payment_records FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_payment_records_no_delete BEFORE DELETE ON payment_records FOR EACH ROW EXECUTE FUNCTION forbid_delete();

CREATE TABLE receipts
(
    id                uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id      uuid        NOT NULL REFERENCES communities (id),
    receipt_no        varchar(40) NOT NULL,
    payment_record_id uuid        NOT NULL,
    pdf_key           varchar(255),
    emailed_at        timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_receipts_community_receipt_no UNIQUE (community_id, receipt_no),
    CONSTRAINT uq_receipts_payment_record UNIQUE (payment_record_id),
    CONSTRAINT uq_receipts_id_community UNIQUE (id, community_id),
    CONSTRAINT fk_receipts_payment_record FOREIGN KEY (payment_record_id, community_id) REFERENCES payment_records (id, community_id)
);
CREATE INDEX idx_receipts_community ON receipts (community_id);
CREATE TRIGGER trg_receipts_updated_at BEFORE UPDATE ON receipts FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_receipts_no_delete BEFORE DELETE ON receipts FOR EACH ROW EXECUTE FUNCTION forbid_delete();

-- payment_records.receipt_id is added after receipts exist (the two tables reference each other).
ALTER TABLE payment_records
    ADD CONSTRAINT fk_payment_records_receipt FOREIGN KEY (receipt_id, community_id) REFERENCES receipts (id, community_id);
CREATE INDEX idx_payment_records_receipt ON payment_records (receipt_id);
