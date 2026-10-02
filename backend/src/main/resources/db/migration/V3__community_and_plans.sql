-- Communities (the tenants), plans, community admins and platform billing.
--
-- Tenant integrity convention used from here on: every tenant-owned parent exposes
-- UNIQUE (id, community_id) and children reference (parent_id, community_id), so the database itself
-- refuses a row that points at another community's parent.

CREATE TABLE plans
(
    id            uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    code          varchar(30)  NOT NULL,
    name          varchar(100) NOT NULL,
    price_monthly numeric(14, 2) NOT NULL DEFAULT 0,
    price_yearly  numeric(14, 2) NOT NULL DEFAULT 0,
    limits        jsonb        NOT NULL DEFAULT '{}'::jsonb, -- {max_members, storage_mb, emails_per_month}; null value = unlimited
    features      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    is_public     boolean      NOT NULL DEFAULT true,
    active        boolean      NOT NULL DEFAULT true,
    sort_order    integer      NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_plans_code UNIQUE (code),
    CONSTRAINT ck_plans_code_format CHECK (code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT ck_plans_prices CHECK (price_monthly >= 0 AND price_yearly >= 0),
    CONSTRAINT ck_plans_limits_object CHECK (jsonb_typeof(limits) = 'object'),
    CONSTRAINT ck_plans_features_object CHECK (jsonb_typeof(features) = 'object')
);
CREATE TRIGGER trg_plans_updated_at BEFORE UPDATE ON plans FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE communities
(
    id                          uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    name                        varchar(200) NOT NULL,
    slug                        varchar(80)  NOT NULL,
    owner_user_id               uuid REFERENCES users (id),
    contact_name                varchar(150),
    contact_email               citext,
    contact_phone               varchar(30),
    address_line1               varchar(200),
    address_line2               varchar(200),
    city                        varchar(100),
    state                       varchar(100),
    postal_code                 varchar(20),
    country                     varchar(2)   NOT NULL DEFAULT 'IN',
    date_of_establishment       date,
    status                      varchar(20)  NOT NULL DEFAULT 'PENDING',
    plan_id                     uuid         NOT NULL REFERENCES plans (id),
    currency                    varchar(3)   NOT NULL DEFAULT 'INR',
    upi_id                      varchar(100),
    upi_payee_name              varchar(150),
    logo_key                    varchar(255),
    financial_year_start_month  smallint     NOT NULL DEFAULT 4, -- 4 = April (Indian FY); 1 = calendar year
    settings                    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    version                     bigint       NOT NULL DEFAULT 0,
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    updated_at                  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_communities_slug UNIQUE (slug),
    CONSTRAINT ck_communities_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_communities_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_communities_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_communities_country CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_communities_fy_start_month CHECK (financial_year_start_month BETWEEN 1 AND 12),
    CONSTRAINT ck_communities_settings_object CHECK (jsonb_typeof(settings) = 'object')
);
CREATE INDEX idx_communities_status ON communities (status);
CREATE INDEX idx_communities_plan ON communities (plan_id);
CREATE INDEX idx_communities_owner ON communities (owner_user_id);
CREATE INDEX idx_communities_contact_email ON communities (contact_email);
CREATE TRIGGER trg_communities_updated_at BEFORE UPDATE ON communities FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE community_users
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id uuid        NOT NULL REFERENCES communities (id),
    user_id      uuid        NOT NULL REFERENCES users (id),
    role         varchar(20) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_users_community_user UNIQUE (community_id, user_id),
    CONSTRAINT ck_community_users_role CHECK (role IN ('OWNER', 'ADMIN'))
);
CREATE INDEX idx_community_users_community ON community_users (community_id);
CREATE INDEX idx_community_users_user ON community_users (user_id);
CREATE UNIQUE INDEX uq_community_users_one_owner ON community_users (community_id) WHERE role = 'OWNER';
CREATE TRIGGER trg_community_users_updated_at BEFORE UPDATE ON community_users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Platform billing: what a community pays Amanah Connect (not member dues).
CREATE TABLE platform_subscriptions
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id uuid           NOT NULL REFERENCES communities (id),
    plan_id      uuid           NOT NULL REFERENCES plans (id),
    period_start date           NOT NULL,
    period_end   date           NOT NULL,
    amount       numeric(14, 2) NOT NULL,
    reference    varchar(100),
    status       varchar(20)    NOT NULL DEFAULT 'ACTIVE',
    recorded_by  uuid           NOT NULL REFERENCES users (id),
    created_at   timestamptz    NOT NULL DEFAULT now(),
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ck_platform_subscriptions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_platform_subscriptions_amount CHECK (amount >= 0),
    CONSTRAINT ck_platform_subscriptions_period CHECK (period_end > period_start)
);
CREATE INDEX idx_platform_subscriptions_community ON platform_subscriptions (community_id);
CREATE INDEX idx_platform_subscriptions_community_status ON platform_subscriptions (community_id, status);
CREATE INDEX idx_platform_subscriptions_expiry ON platform_subscriptions (status, period_end);
CREATE INDEX idx_platform_subscriptions_plan ON platform_subscriptions (plan_id);
CREATE TRIGGER trg_platform_subscriptions_updated_at BEFORE UPDATE ON platform_subscriptions FOR EACH ROW EXECUTE FUNCTION set_updated_at();
