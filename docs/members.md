# Settings, members, invites and self-registration

Everything under `/api/v1/community/**` is for a COMMUNITY_ADMIN and acts on the community of the signed-in
admin only (see [`tenancy.md`](tenancy.md)). Members have no login: they get everything by email.

## Settings

`GET/PATCH /community/settings`: profile (name, contact, address, date of establishment), logo, currency,
financial year start month, UPI ID and payee name, the word the community uses for a member's group ("Flat",
"Family", "Batch"; default "Group") and notification preferences (due-reminder days before the due date, overdue
reminder frequency, welcome email on/off, receipt email on/off). `PATCH` is partial: a missing field is unchanged
and an empty string clears an optional text field. An optional `version` makes a stale edit a 409.

* **UPI ID** must look like `name@bank`; a UPI ID needs a payee name (it is shown to the payer).
* **Currency and financial year start month lock** as soon as any invoice, payment or ledger entry exists
  (`409 SETTING_LOCKED`): changing them would reinterpret money already recorded. Restating the current value is
  fine. `financialSettingsLocked` in the response says which state you are in.
* **Logo**: `POST /settings/logo/upload-url` with the content type and exact size returns a signed S3 `PUT` URL and
  the headers to send. PNG, JPEG or WebP only (never SVG: it can carry script), at most 512 KB. Then
  `PATCH /settings {"logoKey": ...}`; the server checks the key belongs to this community and looks at what is
  really in storage (type and size) before accepting it. `DELETE /settings/logo` removes it. Without
  `S3_BUCKET` configured, logo calls answer `503 STORAGE_UNAVAILABLE`; nothing else is affected.

## Members

* Fields: full name, email, phone, group, joined date (default today), `consentEmail`, and `customFields`
  (a flat object of at most 20 fields; names are `lower_snake_case`; values are short text, numbers or booleans).
* **Member numbers** are generated, never typed: the community's slug as an upper-case prefix (at most 8
  characters) and a counter that does not restart, e.g. `GARDENSO-0042`. A number already taken (by an import)
  is skipped; a create that fails (plan limit) gives its number back.
* **List**: `q` searches name, member number, email and phone (phone also matches digits only, so `98765 43210`
  finds `+91 98765-43210`); filter by `status` and `group` (case-insensitive); sort by name, memberNo, status,
  group, joinedOn, createdAt; page size at most 100.
* **Deactivate** needs a reason; **activate** takes an optional one. Both are audited.
* **Delete is soft.** A member with unpaid invoices (ISSUED, PARTIAL, OVERDUE) cannot be deleted
  (`409 MEMBER_HAS_UNPAID_INVOICES`, with the count and the amount outstanding) unless `force=true` **and** a
  `reason` are given. The invoices are never touched; the audit entry records that the deletion was forced and
  what was outstanding. A deleted member disappears from the API and releases its email address.
* **Plan limit** (`max_members`) is enforced on create, import confirm and registration approval (`402`). All
  three take the same per-community lock before counting, so concurrent requests cannot overshoot it.
* **Duplicate email**: within a community, an address already used by another member is refused
  (`409 DUPLICATE_EMAIL`, naming the existing member number), case-insensitively. A household can share an address
  on purpose by sending `allowDuplicateEmail: true` (create, update, approve). Another community may use the same
  address freely. In a CSV import a shared address is a warning on the report, not an error.
* **Welcome email** on creation (and on approval) when the setting is on, the member has an email and has given
  consent. It is queued in the outbox on the community's own email quota; over quota the member is still created
  and the welcome is skipped with a warning. Imports send nothing.
* **Counts**: total, active, inactive, new this month (IST) and registrations waiting for review.
* **Export**: `GET /members/export` streams CSV (a page at a time, with the same filters as the list), with a
  UTF-8 byte-order mark for Excel, cells that start with `= + - @` neutralised against formula injection, and an
  audit entry (`MEMBERS_EXPORTED`).
* Audit entries hold ids, status and flags, **not** names, emails or phone numbers.

## CSV import

`POST /members/import` (multipart: `batchId` and a `file`) validates every row and reports reasons; nothing is
written. `POST /members/import/{batchId}/confirm` applies exactly those rows in one transaction, after
re-checking the plan limit (`402`) and the community's data (`409 IMPORT_CONFLICT`). It is idempotent per
`batchId` and works like the platform import described in [`admin.md`](admin.md), except that `member_no` is
optional (blank means "generate one") and only members are imported. Columns: `full_name` (required),
`member_no`, `email`, `phone`, `group`, `status`, `joined_on`, `consent_email`.

## Invite links and self-registration

1. `POST /member-invites` creates a link (default 14 days, 50 registrations, optional fixed group) and returns the
   link and a QR code (PNG, base64) **once**. Only the SHA-256 of the 256-bit token is stored, so neither can be
   shown again. `POST /member-invites/email` creates a single-use link and emails it to one address (this counts
   against the email quota). List, read and revoke (`POST /member-invites/{id}/revoke`, effective at once).
   State is derived: `ACTIVE`, `EXPIRED`, `USED_UP`, `REVOKED`.
2. A visitor opens `GET /public/invites/{token}`: community name, logo, and the form (the group field carries the
   community's own word, and is hidden when the link fixes the group). Anything wrong with a link (unknown,
   malformed, expired, revoked, used up, community not active) gets the **same** `404 INVITE_UNAVAILABLE`, so the
   page reveals nothing about which communities or links exist.
3. `POST /public/invites/{token}/register` (name, email, phone, group, consent, honeypot `website`) creates a
   `PENDING` registration and emails the community's admins. It answers 202 whether or not the address already
   belongs to a member and when the honeypot is filled (nothing is then stored or used). A repeat of an address
   already waiting changes nothing. Each registration uses one of the link's uses; concurrent registrations cannot
   exceed the maximum. Limits: 30 views a minute and 10 registrations an hour per IP, bodies over 16 KB refused.
4. The admin reviews `GET /registrations?status=`, then `POST /registrations/{id}/approve` (creates the member:
   number, plan limit, duplicate check, welcome email; all or nothing) or `/reject` (a reason is required). A
   pending registration shows `emailUsedByMemberNo` when its address belongs to a member already.

## Decisions and limits

* A registration needs an email address (members are reached by email); phone is optional.
* The signed logo URL on a public invite page contains the storage key, which includes the community's id. The id
  is an identifier, not a capability: nothing is granted by knowing it.
* There is no cap on pending registrations beyond the link's maximum uses and the per-IP rate limit.
* Emails are queued in the outbox; there is no sender job yet.
