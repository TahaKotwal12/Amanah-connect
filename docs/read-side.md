# Read side: dashboard, audit trail, data export, erasure

## Dashboard — `GET /api/v1/community/dashboard`

One call for the home screen: members (total / active), this month's billing (billed, collected, outstanding), overdue
count and amount, net balance (opening balance + ledger), open complaints (and how many breached their target), unread
support replies, invoices falling due in the next 14 days, the last 10 things done in the community (in plain language)
and a 12-month collection trend (zeros for empty months).

* Money is a string with two decimals. "This month" and the trend use India time.
* Four queries in total, whatever the data size (a test counts statements). Indexes: `idx_invoices_community_open_due`,
  `idx_invoices_community_issued_on` (V13).
* Cached per community in memory for 30 s (Caffeine, `app.dashboard.cache-seconds`). Another instance may show
  figures up to 30 s old; nothing is written to the cache from request input.

## Audit trail

* `GET /api/v1/admin/audit` (SUPER_ADMIN): filters `actor`, `communityId`, `action`/`actionPrefix`, `entityType`, `entityId`,
  `from`/`to` (a date in India time or a UTC instant; a date in `to` includes that day). Newest first, keyset paging on
  `(created_at, id)`: pass `nextCursor` back as `cursor`; rows are never skipped or repeated while new ones arrive. `limit` 1–200.
* `GET /api/v1/admin/audit/export`: same filters, CSV streamed from a DB cursor, at most 100,000 rows
  (`X-Export-Rows`, `X-Export-Truncated`). Cells that start with `=`, `+`, `-` or `@` are neutralised. The export is itself
  audited (`AUDIT_EXPORTED`) and never contains its own entry.
* `GET /api/v1/community/audit` (COMMUNITY_ADMIN): the same, read-only, only the caller's community, no IPs/devices/request ids,
  platform staff shown as "Amanah Connect staff".
* The trail holds ids, numbers and counts, never names or emails (complaint audit records the subject's length, not its text).
  It is append-only, so **a reason typed into an audit-recorded field is permanent**.

## Data export — `/api/v1/community/data-exports`

`POST` answers 202; a job (every 30 s, ShedLock) writes one CSV per tenant table plus `communities.csv` and `community_admins.csv`
into a ZIP using a REPEATABLE READ snapshot and a temp file (memory stays flat), uploads it to S3 and emails the requester
a link valid 48 h (`app.exports.link-validity`).

* One at a time (409 `EXPORT_IN_PROGRESS`), 3 per 24 h (429).
* The emailed link is `/api/v1/public/exports/{token}` → 302 to a 5-minute presigned URL. Only the SHA-256 of the token is stored,
  and the mail's payload is erased once sent. Unknown, expired and malformed links all answer 404 `EXPORT_LINK_UNAVAILABLE`.
  Signed-in admins can get a fresh address from `GET /{id}/download-url`.
* Secrets (`token_hash`, `password_hash`, `request_hash`) are never exported. A test fails if a new tenant table is not in the ZIP.
* After the link expires the ZIP is deleted from S3 and the export shows `EXPIRED`. A crashed run is retried after 30 min.
* A suspended community cannot request an export (it is write-blocked like every other POST).

## Member erasure — `POST /api/v1/community/members/{id}/anonymise`

Needs `memberNo` typed back and a `reason`. Irreversible. Removes: name, email, phone, group, custom fields, consent, the
registration form, the free text of the member's complaints and comments, names/links in emails addressed to them (pending ones
are cancelled) and stored receipt PDFs (re-rendered from anonymised data when asked). Keeps: the member row as "Erased member"
with its number (soft-deleted, INACTIVE), and every invoice, payment, receipt and ledger entry unchanged, so totals, numbering and
outstanding balances are identical before and after. The response says what was kept and removed.

Not covered: free text a community typed into invoice descriptions, ledger titles or notes that names the person, and
copies the community already downloaded.
