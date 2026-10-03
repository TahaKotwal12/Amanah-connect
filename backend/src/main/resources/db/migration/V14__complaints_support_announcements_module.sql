-- Complaints, support chat and announcements modules: the columns the first schema did not have.

-- ---- complaints ------------------------------------------------------------------------------------------------------
ALTER TABLE complaints
    ADD COLUMN category  varchar(60),
    ADD COLUMN closed_at timestamptz;
ALTER TABLE complaints
    ADD CONSTRAINT ck_complaints_closed_at CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL);
CREATE INDEX idx_complaints_community_created ON complaints (community_id, created_at DESC);

-- ---- support chat ----------------------------------------------------------------------------------------------------
-- seq numbers the messages of a thread 1, 2, 3 ... under the thread row lock, so "messages after seq N" can never miss
-- one that committed late (a created_at cursor could).
ALTER TABLE support_threads
    ADD COLUMN assigned_to           uuid REFERENCES users (id),
    ADD COLUMN closed_at             timestamptz,
    ADD COLUMN message_seq           bigint      NOT NULL DEFAULT 0,
    ADD COLUMN community_notified_at timestamptz,
    ADD COLUMN platform_notified_at  timestamptz;
ALTER TABLE support_threads
    ADD CONSTRAINT ck_support_threads_closed_at CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL);
CREATE INDEX idx_support_threads_assigned_to ON support_threads (assigned_to);

ALTER TABLE support_messages
    ADD COLUMN seq                     bigint,
    ADD COLUMN sender_side             varchar(10),
    ADD COLUMN attachment_name         varchar(200),
    ADD COLUMN attachment_content_type varchar(100),
    ADD COLUMN attachment_size         bigint;
UPDATE support_messages m
SET sender_side = CASE WHEN u.role = 'SUPER_ADMIN' THEN 'PLATFORM' ELSE 'COMMUNITY' END
FROM users u
WHERE u.id = m.sender_user_id;
UPDATE support_messages m
SET seq = n.rn
FROM (SELECT id, row_number() OVER (PARTITION BY thread_id ORDER BY created_at, id) AS rn FROM support_messages) n
WHERE n.id = m.id;
UPDATE support_threads t
SET message_seq = coalesce((SELECT max(seq) FROM support_messages m WHERE m.thread_id = t.id), 0);
ALTER TABLE support_messages
    ALTER COLUMN seq SET NOT NULL,
    ALTER COLUMN sender_side SET NOT NULL,
    ADD CONSTRAINT ck_support_messages_side CHECK (sender_side IN ('COMMUNITY', 'PLATFORM')),
    ADD CONSTRAINT uq_support_messages_thread_seq UNIQUE (thread_id, seq),
    ADD CONSTRAINT ck_support_messages_attachment CHECK ((attachment_key IS NULL) = (attachment_content_type IS NULL));
CREATE INDEX idx_support_messages_unread ON support_messages (community_id, sender_side) WHERE read_at IS NULL;

-- ---- announcements ---------------------------------------------------------------------------------------------------
ALTER TABLE announcements
    ADD COLUMN kind                varchar(20) NOT NULL DEFAULT 'ANNOUNCEMENT',
    ADD COLUMN banner              boolean     NOT NULL DEFAULT false,
    ADD COLUMN expires_at          timestamptz,
    ADD COLUMN recipients_total    integer     NOT NULL DEFAULT 0,
    ADD COLUMN emails_queued       integer     NOT NULL DEFAULT 0,
    ADD COLUMN skipped_no_email    integer     NOT NULL DEFAULT 0,
    ADD COLUMN skipped_no_consent  integer     NOT NULL DEFAULT 0,
    ADD COLUMN skipped_quota       integer     NOT NULL DEFAULT 0;
ALTER TABLE announcements
    ADD CONSTRAINT ck_announcements_kind CHECK (kind IN ('ANNOUNCEMENT', 'OFFER', 'MAINTENANCE')),
    ADD CONSTRAINT ck_announcements_counts CHECK (recipients_total >= 0 AND emails_queued >= 0 AND skipped_no_email >= 0 AND skipped_no_consent >= 0 AND skipped_quota >= 0);
CREATE INDEX idx_announcements_platform_sent ON announcements (status, sent_at DESC) WHERE community_id IS NULL;

-- Which community admin has seen which platform announcement (the notification area's read state).
CREATE TABLE announcement_reads
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    announcement_id uuid        NOT NULL REFERENCES announcements (id),
    user_id         uuid        NOT NULL REFERENCES users (id),
    read_at         timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_announcement_reads UNIQUE (announcement_id, user_id)
);
CREATE INDEX idx_announcement_reads_user ON announcement_reads (user_id);
CREATE TRIGGER trg_announcement_reads_updated_at BEFORE UPDATE ON announcement_reads FOR EACH ROW EXECUTE FUNCTION set_updated_at();
