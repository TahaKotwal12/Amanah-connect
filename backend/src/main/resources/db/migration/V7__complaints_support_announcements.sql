-- Complaints (members' complaints logged by admins), support helpdesk (community admin <-> super admin)
-- and announcements.

CREATE TABLE complaints
(
    id           uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id uuid         NOT NULL REFERENCES communities (id),
    member_id    uuid,
    subject      varchar(200) NOT NULL,
    description  text         NOT NULL,
    status       varchar(20)  NOT NULL DEFAULT 'OPEN',
    priority     varchar(10)  NOT NULL DEFAULT 'MEDIUM',
    assigned_to  uuid REFERENCES users (id),
    created_by   uuid         NOT NULL REFERENCES users (id),
    resolved_at  timestamptz,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_complaints_id_community UNIQUE (id, community_id),
    CONSTRAINT fk_complaints_member FOREIGN KEY (member_id, community_id) REFERENCES members (id, community_id),
    CONSTRAINT ck_complaints_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_complaints_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_complaints_resolved_at CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL)
);
CREATE INDEX idx_complaints_community ON complaints (community_id);
CREATE INDEX idx_complaints_community_status ON complaints (community_id, status);
CREATE INDEX idx_complaints_community_member ON complaints (community_id, member_id);
CREATE INDEX idx_complaints_assigned_to ON complaints (assigned_to);
CREATE TRIGGER trg_complaints_updated_at BEFORE UPDATE ON complaints FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE complaint_comments
(
    id             uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id   uuid        NOT NULL REFERENCES communities (id),
    complaint_id   uuid        NOT NULL,
    author_user_id uuid        NOT NULL REFERENCES users (id),
    body           text        NOT NULL,
    internal       boolean     NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_complaint_comments_complaint FOREIGN KEY (complaint_id, community_id) REFERENCES complaints (id, community_id),
    CONSTRAINT ck_complaint_comments_body CHECK (length(btrim(body)) > 0)
);
CREATE INDEX idx_complaint_comments_community ON complaint_comments (community_id);
CREATE INDEX idx_complaint_comments_complaint ON complaint_comments (complaint_id, created_at);
CREATE TRIGGER trg_complaint_comments_updated_at BEFORE UPDATE ON complaint_comments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE support_threads
(
    id              uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id    uuid         NOT NULL REFERENCES communities (id),
    subject         varchar(200) NOT NULL,
    status          varchar(20)  NOT NULL DEFAULT 'OPEN',
    priority        varchar(10)  NOT NULL DEFAULT 'MEDIUM',
    created_by      uuid         NOT NULL REFERENCES users (id),
    last_message_at timestamptz  NOT NULL DEFAULT now(),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_support_threads_id_community UNIQUE (id, community_id),
    CONSTRAINT ck_support_threads_status CHECK (status IN ('OPEN', 'WAITING', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_support_threads_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT'))
);
CREATE INDEX idx_support_threads_community ON support_threads (community_id);
CREATE INDEX idx_support_threads_community_status ON support_threads (community_id, status);
CREATE INDEX idx_support_threads_status_last_message ON support_threads (status, last_message_at);
CREATE TRIGGER trg_support_threads_updated_at BEFORE UPDATE ON support_threads FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE support_messages
(
    id             uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    community_id   uuid        NOT NULL REFERENCES communities (id),
    thread_id      uuid        NOT NULL,
    sender_user_id uuid        NOT NULL REFERENCES users (id),
    body           text        NOT NULL,
    attachment_key varchar(255),
    read_at        timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_support_messages_thread FOREIGN KEY (thread_id, community_id) REFERENCES support_threads (id, community_id),
    CONSTRAINT ck_support_messages_body CHECK (length(btrim(body)) > 0)
);
CREATE INDEX idx_support_messages_community ON support_messages (community_id);
CREATE INDEX idx_support_messages_thread ON support_messages (thread_id, created_at);
CREATE TRIGGER trg_support_messages_updated_at BEFORE UPDATE ON support_messages FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- community_id NULL = platform-wide announcement from the super admin to community admins.
CREATE TABLE announcements
(
    id              uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    community_id    uuid REFERENCES communities (id),
    title           varchar(200) NOT NULL,
    body            text         NOT NULL,
    audience        varchar(20)  NOT NULL,
    audience_filter jsonb        NOT NULL DEFAULT '{}'::jsonb, -- {"group": "..."} or {"memberIds": ["..."]}
    send_email      boolean      NOT NULL DEFAULT false,
    status          varchar(20)  NOT NULL DEFAULT 'DRAFT',
    scheduled_at    timestamptz,
    sent_at         timestamptz,
    created_by      uuid         NOT NULL REFERENCES users (id),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_announcements_audience CHECK (audience IN ('ALL_ACTIVE', 'GROUP', 'SELECTED', 'COMMUNITY_ADMINS')),
    CONSTRAINT ck_announcements_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'SENT')),
    CONSTRAINT ck_announcements_scheduled CHECK (status <> 'SCHEDULED' OR scheduled_at IS NOT NULL),
    CONSTRAINT ck_announcements_sent CHECK (status <> 'SENT' OR sent_at IS NOT NULL),
    CONSTRAINT ck_announcements_scope CHECK ((community_id IS NULL) = (audience = 'COMMUNITY_ADMINS')),
    CONSTRAINT ck_announcements_filter_object CHECK (jsonb_typeof(audience_filter) = 'object')
);
CREATE INDEX idx_announcements_community ON announcements (community_id);
CREATE INDEX idx_announcements_community_status ON announcements (community_id, status);
CREATE INDEX idx_announcements_due ON announcements (status, scheduled_at);
CREATE TRIGGER trg_announcements_updated_at BEFORE UPDATE ON announcements FOR EACH ROW EXECUTE FUNCTION set_updated_at();
