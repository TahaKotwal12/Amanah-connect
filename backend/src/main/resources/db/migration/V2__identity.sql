-- Identity: platform users (SUPER_ADMIN, COMMUNITY_ADMIN), refresh tokens, 2FA recovery codes.
-- Members have no login and are not rows in this schema.

-- Shared trigger function: backstop that keeps updated_at correct for writes that bypass JPA.
-- It only acts when the statement did not change updated_at itself, so a value written by the
-- application is never overridden. clock_timestamp() is used because now() is the transaction start.
CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
        NEW.updated_at = clock_timestamp();
    END IF;
    RETURN NEW;
END
$$;

CREATE TABLE users
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    email           citext      NOT NULL,
    password_hash   varchar(100),
    full_name       varchar(150) NOT NULL,
    role            varchar(20) NOT NULL,
    status          varchar(20) NOT NULL DEFAULT 'INVITED',
    totp_secret_enc varchar(512),
    totp_enabled    boolean     NOT NULL DEFAULT false,
    failed_attempts integer     NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('SUPER_ADMIN', 'COMMUNITY_ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT ck_users_active_has_password CHECK (status <> 'ACTIVE' OR password_hash IS NOT NULL),
    CONSTRAINT ck_users_totp_has_secret CHECK (NOT totp_enabled OR totp_secret_enc IS NOT NULL)
);
CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE refresh_tokens
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users (id),
    token_hash  varchar(64) NOT NULL, -- hex SHA-256 of the opaque token; the token itself is never stored
    family_id   uuid        NOT NULL, -- all rotations of one login share a family (reuse revokes the family)
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    replaced_by uuid REFERENCES refresh_tokens (id),
    ip          varchar(45),
    user_agent  varchar(512),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
CREATE TRIGGER trg_refresh_tokens_updated_at BEFORE UPDATE ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE recovery_codes
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id),
    code_hash  varchar(100) NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_recovery_codes_user_code UNIQUE (user_id, code_hash)
);
CREATE INDEX idx_recovery_codes_user ON recovery_codes (user_id);
CREATE INDEX idx_recovery_codes_code_hash ON recovery_codes (code_hash);
CREATE TRIGGER trg_recovery_codes_updated_at BEFORE UPDATE ON recovery_codes FOR EACH ROW EXECUTE FUNCTION set_updated_at();
