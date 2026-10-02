-- Ledger: reference category templates, per-community categories and entries.

-- Generic defaults, copied into each new community by trg_communities_defaults (see V9).
CREATE TABLE ledger_category_templates
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    name       varchar(100) NOT NULL,
    type       varchar(10)  NOT NULL,
    sort_order integer      NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_category_templates_name_type UNIQUE (name, type),
    CONSTRAINT ck_ledger_category_templates_type CHECK (type IN ('INCOME', 'EXPENSE'))
);
CREATE TRIGGER trg_ledger_category_templates_updated_at BEFORE UPDATE ON ledger_category_templates FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE ledger_categories
(
    id           uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id uuid         NOT NULL REFERENCES communities (id),
    name         varchar(100) NOT NULL,
    type         varchar(10)  NOT NULL,
    active       boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_categories_community_name_type UNIQUE (community_id, name, type),
    -- lets an entry prove its category belongs to the same community AND has the same type
    CONSTRAINT uq_ledger_categories_id_community_type UNIQUE (id, community_id, type),
    CONSTRAINT ck_ledger_categories_type CHECK (type IN ('INCOME', 'EXPENSE'))
);
CREATE INDEX idx_ledger_categories_community ON ledger_categories (community_id);
CREATE INDEX idx_ledger_categories_community_active ON ledger_categories (community_id, active);
CREATE TRIGGER trg_ledger_categories_updated_at BEFORE UPDATE ON ledger_categories FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE ledger_entries
(
    id              uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id    uuid           NOT NULL REFERENCES communities (id),
    type            varchar(10)    NOT NULL,
    category_id     uuid           NOT NULL,
    amount          numeric(14, 2) NOT NULL, -- positive entry, or negative when reversed_of is set
    entry_date      date           NOT NULL,
    title           varchar(200)   NOT NULL,
    notes           text,
    attachment_key  varchar(255),
    source          varchar(10)    NOT NULL DEFAULT 'MANUAL',
    source_id       uuid, -- payment_records.id when source = PAYMENT
    reversed_of     uuid,
    reversal_reason text,
    created_by      uuid           NOT NULL REFERENCES users (id),
    version         bigint         NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_entries_id_community UNIQUE (id, community_id),
    CONSTRAINT fk_ledger_entries_category FOREIGN KEY (category_id, community_id, type) REFERENCES ledger_categories (id, community_id, type),
    CONSTRAINT fk_ledger_entries_reversed_of FOREIGN KEY (reversed_of, community_id) REFERENCES ledger_entries (id, community_id),
    CONSTRAINT ck_ledger_entries_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_ledger_entries_source CHECK (source IN ('MANUAL', 'PAYMENT')),
    CONSTRAINT ck_ledger_entries_amount_sign CHECK (
        (reversed_of IS NULL AND amount > 0) OR (reversed_of IS NOT NULL AND amount < 0)),
    CONSTRAINT ck_ledger_entries_reversal_reason CHECK (
        reversed_of IS NULL OR length(btrim(coalesce(reversal_reason, ''))) > 0),
    CONSTRAINT ck_ledger_entries_source_id CHECK (
        (source = 'PAYMENT' AND source_id IS NOT NULL) OR (source = 'MANUAL' AND source_id IS NULL))
);
CREATE INDEX idx_ledger_entries_community ON ledger_entries (community_id);
CREATE INDEX idx_ledger_entries_community_entry_date ON ledger_entries (community_id, entry_date);
CREATE INDEX idx_ledger_entries_community_type_date ON ledger_entries (community_id, type, entry_date);
CREATE INDEX idx_ledger_entries_category ON ledger_entries (category_id);
CREATE INDEX idx_ledger_entries_source ON ledger_entries (source, source_id) WHERE source_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ledger_entries_reversed_of ON ledger_entries (reversed_of) WHERE reversed_of IS NOT NULL;
CREATE TRIGGER trg_ledger_entries_updated_at BEFORE UPDATE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_ledger_entries_no_delete BEFORE DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION forbid_delete();
