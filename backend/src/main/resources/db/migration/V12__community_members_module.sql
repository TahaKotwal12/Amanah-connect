-- COMMUNITY_ADMIN member module: member numbering, deactivation/deletion reasons, invite details.

-- Member numbers come from the same gap-free counter table (scope 'ALL': they do not restart each year).
ALTER TABLE document_counters DROP CONSTRAINT ck_document_counters_counter_type;
ALTER TABLE document_counters
    ADD CONSTRAINT ck_document_counters_counter_type CHECK (counter_type IN ('INVOICE', 'RECEIPT', 'MEMBER'));

ALTER TABLE members
    ADD COLUMN status_reason     text,
    ADD COLUMN status_changed_at timestamptz,
    ADD COLUMN delete_reason     text,
    ADD COLUMN deleted_by        uuid REFERENCES users (id),
    ADD CONSTRAINT ck_members_deleted_by CHECK (deleted_at IS NOT NULL OR deleted_by IS NULL);

-- Searching a community's members by email and phone.
CREATE INDEX idx_members_community_phone ON members (community_id, phone);

ALTER TABLE member_invites
    ADD COLUMN default_group_label varchar(100),
    ADD COLUMN invited_email       citext;

-- A person who registers twice with the same address while the first is still waiting creates no second row.
CREATE UNIQUE INDEX uq_member_registrations_pending_email
    ON member_registrations (community_id, email) WHERE status = 'PENDING' AND email IS NOT NULL;
