-- Auth support: single-use hashed tokens (password reset, invitation) and two users columns.

CREATE TABLE auth_tokens
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id),
    purpose    varchar(20) NOT NULL,
    token_hash varchar(64) NOT NULL, -- hex SHA-256; the raw token only ever exists in the email link
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_auth_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_auth_tokens_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_auth_tokens_purpose CHECK (purpose IN ('PASSWORD_RESET', 'INVITATION'))
);
CREATE INDEX idx_auth_tokens_user_purpose ON auth_tokens (user_id, purpose, created_at DESC);
CREATE INDEX idx_auth_tokens_expires_at ON auth_tokens (expires_at);
CREATE TRIGGER trg_auth_tokens_updated_at BEFORE UPDATE ON auth_tokens FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- must_setup_2fa: set for accounts that must enrol in 2FA before doing anything else (the bootstrap
--   super admin; a community that requires 2FA). SUPER_ADMIN is always required regardless.
-- totp_last_used_step: the last accepted 30-second TOTP step, so a code cannot be replayed.
ALTER TABLE users
    ADD COLUMN must_setup_2fa      boolean NOT NULL DEFAULT false,
    ADD COLUMN totp_last_used_step bigint;
