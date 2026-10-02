-- Baseline migration: extensions the schema relies on.
-- citext: case-insensitive text (emails, codes). pgcrypto: gen_random_uuid() and digests.
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
