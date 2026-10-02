# Platform administration

Everything under `/api/v1/admin/**` requires a SUPER_ADMIN whose 2FA is complete. A COMMUNITY_ADMIN gets 403,
a super admin that has not finished 2FA enrolment gets 403 `MFA_SETUP_REQUIRED`, and the account is re-checked on
every admin request, so disabling a super admin takes effect at once (not after the 15-minute token). Every
mutation writes an audit entry; nothing financial is ever deleted.

Browse the API in Swagger UI (`local` profile, `/swagger-ui.html`): groups **Admin**, **Public** and
**Authentication**; the admin group is tagged Communities, Plans, Subscriptions, Leads, Import and Stats.
`springdoc.api-docs.enabled=true` turns the documents on in any other profile (they are off in production).

## Communities

* **Create** → community `PENDING`, owner user `INVITED`, invitation email queued in the outbox. The community
  becomes `ACTIVE` when the owner accepts the invitation, or when a super admin activates it.
* **Suspend / activate / archive** need a reason (suspend, activate), are audited, and email the owner.
  A suspended community can still be read but not written to. Archiving is soft; an archived community can be
  restored with *activate*.
* **Reset admin password** revokes the owner's sessions and sends a reset email (or re-sends the invitation if the
  owner never accepted it).
* **Export** (`?format=json|csv`): profile, owner, member count and subscription history. CSV cells that start with
  `= + - @` are prefixed with `'` so spreadsheets never run them as formulas.
* **Overview** (profile, counts, finance summary, recent activity) is read-only and writes a `SUPPORT_VIEW`
  audit entry on every call.

## Plans and subscriptions

* Plans have limits (`max_members`, `storage_mb`, `emails_per_month`; `null` = unlimited) and boolean features.
  Deactivating a plan only stops it being offered: communities already on it keep it and its limits.
* A subscription records a manual payment (`paidOn`, amount, reference, period). Displayed status is derived:
  `ACTIVE`, `EXPIRING` (ends within `expiring-window-days` and no later subscription renews it), `EXPIRED`,
  `CANCELLED`. Cancelling keeps the row (with a reason). A reference can be used once per community.
* The community runs on the plan of its latest subscription.
* **Daily job, 09:00 IST** (ShedLock, one instance runs it): reminders at 7 and 1 days before expiry, marks lapsed
  subscriptions `EXPIRED` and emails the owner, and logs a warning for communities past the grace period.
  It **never suspends unless `APP_SUBSCRIPTIONS_AUTO_SUSPEND_ENABLED=true`**; then it suspends after
  `APP_SUBSCRIPTIONS_GRACE_DAYS` (default 7) with no valid subscription and emails the owner. Every step is
  idempotent (flags on the row), so a re-run sends nothing twice.

## Leads

`POST /api/v1/public/leads` is open and rate limited per IP (5/hour). It has a honeypot field `website`: when it is
filled the request still answers 202 but nothing is stored or sent. Bodies over 16 KB get 413, text fields have
length limits and control characters are stripped. A stored lead emails the super admins
(`PLATFORM_NOTIFICATION_EMAIL`, else every active super admin) and acknowledges the visitor (at most once an hour
per address).

Super admins list and search leads and move them through `NEW → CONTACTED → DEMO_SCHEDULED → LOST`.
**Convert to community** is a prefilled draft: `GET /admin/leads/{id}/community-draft` returns a create-community
request (with `leadId` and a suggested plan); posting it to `/admin/communities` creates the community and marks
the lead `CONVERTED`. `CONVERTED` cannot be set by hand.

## Importing an existing community (migration)

1. `POST /admin/communities/{id}/import` (multipart): a client-chosen `batchId` (UUID) plus any of the files
   `members`, `openingBalances`, `openInvoices` (CSV, UTF-8, ≤ 2 MB and ≤ 5000 rows each; Excel files are refused).
   This is a **dry run**: every row is validated and the report lists valid and invalid rows with reasons. Nothing
   is written.
2. `POST /admin/communities/{id}/import/{batchId}/confirm` applies exactly the validated rows in one transaction.
   Invalid rows block the confirm unless `{"skipInvalidRows": true}` is sent.

Idempotency: the same `batchId` with the same files returns the same report; a confirmed batch returns its stored
result again; the same `batchId` with different files is `409 IMPORT_BATCH_CONFLICT`. Confirm re-checks plan limits
(`402 PLAN_LIMIT_EXCEEDED`) and uniqueness against the community's current data (`409 IMPORT_CONFLICT` if it
changed since the dry run).

| File | Columns (required in bold) |
| --- | --- |
| members | **member_no**, **full_name**, email, phone, group, status (ACTIVE/INACTIVE), joined_on, consent_email |
| openingBalances | **category**, **type** (INCOME/EXPENSE), **amount**, **as_of**, description |
| openInvoices | **member_no**, **invoice_no**, **amount**, **due_date**, kind, period |

Dates: `yyyy-MM-dd`, `dd/MM/yyyy` or `dd-MM-yyyy`. Amounts: plain decimals with at most two places (`1250.50`).
Categories must already exist and be active in the community.

Decisions worth knowing:

* An imported open invoice carries only what is **still owed** (`amount`, `amount_paid = 0`) under the old
  system's number. It takes no number from the gap-free counters, and numbers shaped like this system's own
  (`INV-2025-26/000001`) are refused so they cannot collide later.
* Opening balances become ordinary manual ledger entries created by the super admin.
* Nothing is emailed to imported members.

## Statistics

`GET /admin/stats` runs one aggregate statement: communities by status (the total excludes archived), members
(not deleted, not in archived communities), platform revenue this month and this calendar year by `paid_on` (IST,
cancelled payments excluded; money as strings, INR), expiring subscriptions, new leads and open support threads.
