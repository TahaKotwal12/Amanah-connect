# Data model

Generated from the migrated PostgreSQL schema by `DataModelDocIT`; do not edit by hand. To regenerate after a migration:

```
./mvnw verify -Dit.test=DataModelDocIT -DargLine="-Dgenerate.docs=true"
```

Every tenant table carries `community_id`; composite foreign keys `(id, community_id)` make it impossible for a row to point at another community's row. Money is `numeric(14,2)`. Financial rows are never deleted (reversal rows instead). `audit_logs` is append-only (database triggers).

## Identity, plans and platform

```mermaid
erDiagram
    communities ||--o{ audit_logs : "community_id"
    users ||--o{ audit_logs : "actor_user_id"
    users ||--o{ auth_tokens : "user_id"
    plans ||--o{ communities : "plan_id"
    users ||--o{ communities : "owner_user_id"
    users ||--o{ communities : "status_changed_by"
    communities ||--o{ community_users : "community_id"
    users ||--o{ community_users : "user_id"
    communities ||--o{ data_exports : "community_id"
    users ||--o{ data_exports : "requested_by"
    communities ||--o{ import_batches : "community_id"
    users ||--o{ import_batches : "confirmed_by"
    users ||--o{ import_batches : "created_by"
    communities ||--o{ leads : "converted_community_id"
    users ||--o{ leads : "handled_by"
    communities ||--o{ platform_subscriptions : "community_id"
    plans ||--o{ platform_subscriptions : "plan_id"
    users ||--o{ platform_subscriptions : "recorded_by"
    users ||--o{ recovery_codes : "user_id"
    refresh_tokens ||--o{ refresh_tokens : "replaced_by"
    users ||--o{ refresh_tokens : "user_id"
    audit_logs {
        uuid id PK
        uuid actor_user_id FK
        uuid community_id FK
        varchar action
        varchar entity_type
        uuid entity_id
        jsonb before
        jsonb after
        varchar ip
        varchar user_agent
        varchar request_id
        timestamptz created_at
    }
    auth_tokens {
        uuid id PK
        uuid user_id FK
        varchar purpose
        varchar token_hash UK
        timestamptz expires_at
        timestamptz used_at
        timestamptz created_at
        timestamptz updated_at
    }
    communities {
        uuid id PK
        varchar name
        varchar slug UK
        uuid owner_user_id FK
        varchar contact_name
        citext contact_email
        varchar contact_phone
        varchar address_line1
        varchar address_line2
        varchar city
        varchar state
        varchar postal_code
        varchar country
        date date_of_establishment
        varchar status
        uuid plan_id FK
        varchar currency
        varchar upi_id
        varchar upi_payee_name
        varchar logo_key
        int2 financial_year_start_month
        jsonb settings
        int8 version
        timestamptz created_at
        timestamptz updated_at
        text status_reason
        timestamptz status_changed_at
        uuid status_changed_by FK
        numeric opening_balance
    }
    community_users {
        uuid id PK
        uuid community_id FK
        uuid user_id FK
        varchar role
        timestamptz created_at
        timestamptz updated_at
    }
    data_exports {
        uuid id PK
        uuid community_id FK
        uuid requested_by FK
        varchar status
        varchar object_key
        int8 size_bytes
        jsonb tables
        varchar token_hash
        timestamptz link_expires_at
        int4 download_count
        text error
        timestamptz started_at
        timestamptz completed_at
        timestamptz created_at
        timestamptz updated_at
    }
    import_batches {
        uuid id PK
        uuid community_id FK
        uuid batch_id
        varchar payload_hash
        varchar status
        jsonb report
        jsonb rows
        bool confirmable
        bool skip_invalid
        jsonb result
        uuid created_by FK
        uuid confirmed_by FK
        timestamptz confirmed_at
        timestamptz created_at
        timestamptz updated_at
    }
    leads {
        uuid id PK
        varchar name
        citext email
        varchar phone
        varchar community_name
        int4 size_estimate
        text message
        varchar status
        uuid handled_by FK
        varchar source
        timestamptz created_at
        timestamptz updated_at
        uuid converted_community_id FK
    }
    plans {
        uuid id PK
        varchar code UK
        varchar name
        numeric price_monthly
        numeric price_yearly
        jsonb limits
        jsonb features
        bool is_public
        bool active
        int4 sort_order
        timestamptz created_at
        timestamptz updated_at
    }
    platform_subscriptions {
        uuid id PK
        uuid community_id FK
        uuid plan_id FK
        date period_start
        date period_end
        numeric amount
        varchar reference
        varchar status
        uuid recorded_by FK
        timestamptz created_at
        timestamptz updated_at
        date paid_on
        text cancel_reason
        timestamptz reminder_7d_sent_at
        timestamptz reminder_1d_sent_at
        timestamptz expired_notified_at
    }
    recovery_codes {
        uuid id PK
        uuid user_id FK
        varchar code_hash
        timestamptz used_at
        timestamptz created_at
        timestamptz updated_at
    }
    refresh_tokens {
        uuid id PK
        uuid user_id FK
        varchar token_hash UK
        uuid family_id
        timestamptz expires_at
        timestamptz revoked_at
        uuid replaced_by FK
        varchar ip
        varchar user_agent
        timestamptz created_at
        timestamptz updated_at
    }
    users {
        uuid id PK
        citext email UK
        varchar password_hash
        varchar full_name
        varchar role
        varchar status
        varchar totp_secret_enc
        bool totp_enabled
        int4 failed_attempts
        timestamptz locked_until
        timestamptz last_login_at
        timestamptz created_at
        timestamptz updated_at
        bool must_setup_2fa
        int8 totp_last_used_step
    }
```

## Members

```mermaid
erDiagram
    communities ||--o{ document_counters : "community_id"
    communities ||--o{ member_invites : "community_id"
    users ||--o{ member_invites : "created_by"
    communities ||--o{ member_registrations : "community_id"
    member_invites ||--o{ member_registrations : "invite_id, community_id"
    members ||--o{ member_registrations : "member_id, community_id"
    users ||--o{ member_registrations : "reviewed_by"
    communities ||--o{ members : "community_id"
    users ||--o{ members : "anonymised_by"
    users ||--o{ members : "deleted_by"
    document_counters {
        uuid id PK
        uuid community_id FK
        varchar counter_type
        varchar financial_year
        int8 last_value
        timestamptz created_at
        timestamptz updated_at
    }
    member_invites {
        uuid id PK
        uuid community_id FK
        varchar token_hash UK
        timestamptz expires_at
        int4 max_uses
        int4 used_count
        timestamptz revoked_at
        uuid created_by FK
        timestamptz created_at
        timestamptz updated_at
        varchar default_group_label
        citext invited_email
    }
    member_registrations {
        uuid id PK
        uuid community_id FK
        uuid invite_id FK
        varchar full_name
        citext email
        varchar phone
        varchar group_label
        jsonb custom_fields
        bool consent_email
        varchar status
        uuid reviewed_by FK
        timestamptz reviewed_at
        text rejection_reason
        uuid member_id FK
        timestamptz created_at
        timestamptz updated_at
        timestamptz anonymised_at
    }
    members {
        uuid id PK
        uuid community_id FK
        varchar member_no
        varchar full_name
        citext email
        varchar phone
        varchar group_label
        varchar status
        date joined_on
        jsonb custom_fields
        bool consent_email
        timestamptz deleted_at
        timestamptz created_at
        timestamptz updated_at
        text status_reason
        timestamptz status_changed_at
        text delete_reason
        uuid deleted_by FK
        timestamptz anonymised_at
        uuid anonymised_by FK
    }
    communities {
        uuid id PK
    }
    users {
        uuid id PK
    }
```

## Billing and ledger

```mermaid
erDiagram
    communities ||--o{ fee_plans : "community_id"
    communities ||--o{ invoice_reminders : "community_id"
    invoices ||--o{ invoice_reminders : "invoice_id, community_id"
    communities ||--o{ invoices : "community_id"
    fee_plans ||--o{ invoices : "fee_plan_id, community_id"
    members ||--o{ invoices : "member_id, community_id"
    users ||--o{ invoices : "cancelled_by"
    communities ||--o{ ledger_categories : "community_id"
    communities ||--o{ ledger_entries : "community_id"
    ledger_categories ||--o{ ledger_entries : "category_id, community_id, type"
    ledger_entries ||--o{ ledger_entries : "reversed_of, community_id"
    users ||--o{ ledger_entries : "created_by"
    communities ||--o{ payment_links : "community_id"
    invoices ||--o{ payment_links : "invoice_id, community_id"
    users ||--o{ payment_links : "created_by"
    communities ||--o{ payment_records : "community_id"
    invoices ||--o{ payment_records : "invoice_id, community_id"
    members ||--o{ payment_records : "member_id, community_id"
    payment_records ||--o{ payment_records : "reversed_of, community_id"
    receipts ||--o{ payment_records : "receipt_id, community_id"
    users ||--o{ payment_records : "recorded_by"
    communities ||--o{ receipts : "community_id"
    payment_records ||--o{ receipts : "payment_record_id, community_id"
    fee_plans {
        uuid id PK
        uuid community_id FK
        varchar name
        varchar kind
        numeric amount
        varchar frequency
        int2 due_day
        varchar applies_to
        jsonb applies_to_filter
        bool active
        timestamptz created_at
        timestamptz updated_at
        bool auto_generate
        varchar last_generated_period
    }
    invoice_reminders {
        uuid id PK
        uuid community_id FK
        uuid invoice_id FK
        varchar kind
        date reminder_date
        timestamptz created_at
        timestamptz updated_at
    }
    invoices {
        uuid id PK
        uuid community_id FK
        uuid member_id FK
        varchar invoice_no
        varchar kind
        varchar period
        numeric amount
        numeric amount_paid
        date due_date
        varchar status
        uuid fee_plan_id FK
        int8 version
        timestamptz created_at
        timestamptz updated_at
        date issued_on
        varchar description
        text cancel_reason
        timestamptz cancelled_at
        uuid cancelled_by FK
    }
    ledger_categories {
        uuid id PK
        uuid community_id FK
        varchar name
        varchar type
        bool active
        timestamptz created_at
        timestamptz updated_at
        varchar system_key
    }
    ledger_category_templates {
        uuid id PK
        varchar name
        varchar type
        int4 sort_order
        timestamptz created_at
        timestamptz updated_at
        varchar system_key
    }
    ledger_entries {
        uuid id PK
        uuid community_id FK
        varchar type FK
        uuid category_id FK
        numeric amount
        date entry_date
        varchar title
        text notes
        varchar attachment_key
        varchar source
        uuid source_id
        uuid reversed_of FK
        text reversal_reason
        uuid created_by FK
        int8 version
        timestamptz created_at
        timestamptz updated_at
    }
    payment_links {
        uuid id PK
        uuid community_id FK
        uuid invoice_id FK
        varchar token_hash UK
        timestamptz expires_at
        timestamptz revoked_at
        uuid created_by FK
        timestamptz created_at
        timestamptz updated_at
    }
    payment_records {
        uuid id PK
        uuid community_id FK
        uuid invoice_id FK
        uuid member_id FK
        numeric amount
        varchar method
        varchar reference
        date received_on
        uuid recorded_by FK
        uuid receipt_id FK
        uuid reversed_of FK
        text reversal_reason
        int8 version
        timestamptz created_at
        timestamptz updated_at
        varchar donor_name
        varchar idempotency_key
        varchar request_hash
    }
    receipts {
        uuid id PK
        uuid community_id FK
        varchar receipt_no
        uuid payment_record_id FK
        varchar pdf_key
        timestamptz emailed_at
        timestamptz created_at
        timestamptz updated_at
    }
    communities {
        uuid id PK
    }
    members {
        uuid id PK
    }
    users {
        uuid id PK
    }
```

## Communication and files

```mermaid
erDiagram
    announcements ||--o{ announcement_reads : "announcement_id"
    users ||--o{ announcement_reads : "user_id"
    communities ||--o{ announcements : "community_id"
    users ||--o{ announcements : "created_by"
    communities ||--o{ complaint_comments : "community_id"
    complaints ||--o{ complaint_comments : "complaint_id, community_id"
    users ||--o{ complaint_comments : "author_user_id"
    communities ||--o{ complaints : "community_id"
    members ||--o{ complaints : "member_id, community_id"
    users ||--o{ complaints : "assigned_to"
    users ||--o{ complaints : "created_by"
    communities ||--o{ email_outbox : "community_id"
    communities ||--o{ notification_settings : "community_id"
    communities ||--o{ stored_files : "community_id"
    users ||--o{ stored_files : "created_by"
    communities ||--o{ support_messages : "community_id"
    support_threads ||--o{ support_messages : "thread_id, community_id"
    users ||--o{ support_messages : "sender_user_id"
    communities ||--o{ support_threads : "community_id"
    users ||--o{ support_threads : "assigned_to"
    users ||--o{ support_threads : "created_by"
    announcement_reads {
        uuid id PK
        uuid announcement_id FK
        uuid user_id FK
        timestamptz read_at
        timestamptz created_at
        timestamptz updated_at
    }
    announcements {
        uuid id PK
        uuid community_id FK
        varchar title
        text body
        varchar audience
        jsonb audience_filter
        bool send_email
        varchar status
        timestamptz scheduled_at
        timestamptz sent_at
        uuid created_by FK
        timestamptz created_at
        timestamptz updated_at
        varchar kind
        bool banner
        timestamptz expires_at
        int4 recipients_total
        int4 emails_queued
        int4 skipped_no_email
        int4 skipped_no_consent
        int4 skipped_quota
    }
    complaint_comments {
        uuid id PK
        uuid community_id FK
        uuid complaint_id FK
        uuid author_user_id FK
        text body
        bool internal
        timestamptz created_at
        timestamptz updated_at
    }
    complaints {
        uuid id PK
        uuid community_id FK
        uuid member_id FK
        varchar subject
        text description
        varchar status
        varchar priority
        uuid assigned_to FK
        uuid created_by FK
        timestamptz resolved_at
        timestamptz created_at
        timestamptz updated_at
        varchar category
        timestamptz closed_at
    }
    email_outbox {
        uuid id PK
        uuid community_id FK
        citext to_email
        varchar template
        jsonb payload
        varchar status
        int4 attempts
        timestamptz next_attempt_at
        varchar ses_message_id
        text error
        timestamptz sent_at
        timestamptz created_at
        timestamptz updated_at
        timestamptz last_attempt_at
    }
    email_suppressions {
        uuid id PK
        citext email UK
        varchar reason
        varchar source
        jsonb detail
        timestamptz created_at
        timestamptz updated_at
    }
    notification_settings {
        uuid id PK
        uuid community_id FK
        int4 due_reminder_days_before
        int4 overdue_reminder_every_days
        bool send_welcome
        bool send_receipt
        timestamptz created_at
        timestamptz updated_at
    }
    stored_files {
        uuid id PK
        uuid community_id FK
        varchar kind
        varchar object_key UK
        varchar content_type
        int8 size_bytes
        uuid created_by FK
        timestamptz deleted_at
        timestamptz created_at
        timestamptz updated_at
    }
    support_messages {
        uuid id PK
        uuid community_id FK
        uuid thread_id FK
        uuid sender_user_id FK
        text body
        varchar attachment_key
        timestamptz read_at
        timestamptz created_at
        timestamptz updated_at
        int8 seq
        varchar sender_side
        varchar attachment_name
        varchar attachment_content_type
        int8 attachment_size
    }
    support_threads {
        uuid id PK
        uuid community_id FK
        varchar subject
        varchar status
        varchar priority
        uuid created_by FK
        timestamptz last_message_at
        timestamptz created_at
        timestamptz updated_at
        uuid assigned_to FK
        timestamptz closed_at
        int8 message_seq
        timestamptz community_notified_at
        timestamptz platform_notified_at
    }
    communities {
        uuid id PK
    }
    members {
        uuid id PK
    }
    users {
        uuid id PK
    }
```

## Indexes and constraints

See the migrations in `backend/src/main/resources/db/migration`; they are the source of truth. `SchemaMigrationIT` and `ConstraintsIT` check that the tables exist and that the constraints reject bad data.
