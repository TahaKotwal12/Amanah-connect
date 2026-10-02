-- Members (no login), invite links and public self-registrations awaiting approval.

CREATE TABLE members
(
    id            uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id  uuid         NOT NULL REFERENCES communities (id),
    member_no     varchar(30)  NOT NULL,
    full_name     varchar(150) NOT NULL,
    email         citext,
    phone         varchar(30),
    group_label   varchar(100),
    status        varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    joined_on     date,
    custom_fields jsonb        NOT NULL DEFAULT '{}'::jsonb,
    consent_email boolean      NOT NULL DEFAULT false,
    deleted_at    timestamptz, -- soft delete: members are referenced by financial history
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_members_community_member_no UNIQUE (community_id, member_no),
    CONSTRAINT uq_members_id_community UNIQUE (id, community_id),
    CONSTRAINT ck_members_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_members_custom_fields_object CHECK (jsonb_typeof(custom_fields) = 'object')
);
CREATE INDEX idx_members_community ON members (community_id);
CREATE INDEX idx_members_community_status ON members (community_id, status);
CREATE INDEX idx_members_community_email ON members (community_id, email);
CREATE INDEX idx_members_email ON members (email);
CREATE INDEX idx_members_community_group ON members (community_id, group_label);
CREATE TRIGGER trg_members_updated_at BEFORE UPDATE ON members FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE member_invites
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id uuid        NOT NULL REFERENCES communities (id),
    token_hash   varchar(64) NOT NULL, -- hex SHA-256; the raw token is shown once and never stored
    expires_at   timestamptz NOT NULL,
    max_uses     integer     NOT NULL DEFAULT 1,
    used_count   integer     NOT NULL DEFAULT 0,
    revoked_at   timestamptz,
    created_by   uuid        NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_member_invites_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_member_invites_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_member_invites_id_community UNIQUE (id, community_id),
    CONSTRAINT ck_member_invites_max_uses CHECK (max_uses > 0),
    CONSTRAINT ck_member_invites_used_count CHECK (used_count >= 0 AND used_count <= max_uses)
);
CREATE INDEX idx_member_invites_community ON member_invites (community_id);
CREATE INDEX idx_member_invites_community_expiry ON member_invites (community_id, expires_at);
CREATE TRIGGER trg_member_invites_updated_at BEFORE UPDATE ON member_invites FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE member_registrations
(
    id               uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id     uuid         NOT NULL REFERENCES communities (id),
    invite_id        uuid         NOT NULL,
    full_name        varchar(150) NOT NULL,
    email            citext,
    phone            varchar(30),
    group_label      varchar(100),
    custom_fields    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    consent_email    boolean      NOT NULL DEFAULT false,
    status           varchar(20)  NOT NULL DEFAULT 'PENDING',
    reviewed_by      uuid REFERENCES users (id),
    reviewed_at      timestamptz,
    rejection_reason text,
    member_id        uuid, -- set when approved
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_member_registrations_invite FOREIGN KEY (invite_id, community_id) REFERENCES member_invites (id, community_id),
    CONSTRAINT fk_member_registrations_member FOREIGN KEY (member_id, community_id) REFERENCES members (id, community_id),
    CONSTRAINT ck_member_registrations_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_member_registrations_reviewed CHECK (status = 'PENDING' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT ck_member_registrations_approved_member CHECK (status <> 'APPROVED' OR member_id IS NOT NULL),
    CONSTRAINT ck_member_registrations_custom_fields_object CHECK (jsonb_typeof(custom_fields) = 'object')
);
CREATE INDEX idx_member_registrations_community ON member_registrations (community_id);
CREATE INDEX idx_member_registrations_community_status ON member_registrations (community_id, status);
CREATE INDEX idx_member_registrations_invite ON member_registrations (invite_id);
CREATE INDEX idx_member_registrations_email ON member_registrations (email);
CREATE TRIGGER trg_member_registrations_updated_at BEFORE UPDATE ON member_registrations FOR EACH ROW EXECUTE FUNCTION set_updated_at();
