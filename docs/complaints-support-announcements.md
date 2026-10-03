# Complaints, support chat and announcements

Community endpoints live under `/api/v1/community/**` (COMMUNITY_ADMIN, tenant from the signed-in admin only; see
[`tenancy.md`](tenancy.md)). Platform endpoints live under `/api/v1/admin/**` (SUPER_ADMIN with 2FA completed).
Members have no login: everything that reaches a member is an email, and only to a member who has an address **and**
agreed to email.

## Complaints (`/community/complaints`)

* A complaint has a subject, description, priority (`LOW`/`MEDIUM`/`HIGH`/`URGENT`), free-text category, an optional
  member (a complaint can be about the community in general), an assignee (must be an active admin of the same
  community) and a status: `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`.
* Complaints are **never deleted**; they are closed. A closed complaint takes no edits or comments until it is reopened.
* `POST /{id}/status` changes the status. `RESOLVED` stamps `resolvedAt`; closing a resolved complaint keeps its
  resolved time; going back to `OPEN`/`IN_PROGRESS` clears `resolvedAt` and `closedAt`. An optional `note` is kept as a
  comment.
* **Comments** are `INTERNAL` (admins only, never emailed) or `MEMBER` (a visible update). `visibility` is mandatory so
  nobody discloses something by omission. With `notifyMember: true` (status change or member comment) the member is
  emailed, and the response says what happened in `emailOutcome`: `QUEUED`, `NO_ADDRESS`, `NO_CONSENT` or `QUOTA`. The
  update itself never fails because of email. These emails count against the community's monthly email quota.
* **SLA indicator**: each complaint has `ageDays` (created to resolved/closed, or to now while active), `slaTargetDays`
  (urgent 1, high 3, medium 7, low 14) and `slaBreached` (only for active complaints past their target).
* `GET /` filters: `status` (repeatable), `priority`, `category`, `memberId`, `assignedTo`, `unassigned`, `mine`,
  `slaBreached`, `createdFrom`/`createdTo` (dates, India time) and `q` (subject, description, member name or number).
  `GET /counts` is the dashboard: totals by status, active, SLA-breached, urgent, unassigned, assigned to me, and active
  complaints by category and priority.

## Support chat

A helpdesk between a community's admins and the platform's super admins.

| | Community admin | Super admin |
| --- | --- | --- |
| Threads | `/community/support/threads`: **only their own community's** (any admin of the community) | `/admin/support/threads`: all, with filters |
| Start a thread | yes | no |
| Reply | yes | yes |
| Status / priority / assign / close | no | `PATCH /admin/support/threads/{id}` |
| Unread, summary | `/community/support/summary` | `/admin/support/summary`, `/admin/support/counts` |

* Another community's thread is a `404`, the same as a missing one.
* Statuses: a community message (re)opens a thread (`OPEN`); a platform reply sets `WAITING` (for the community); super
  admins can set `RESOLVED` or `CLOSED`. A `CLOSED` thread takes no messages from either side (`409 THREAD_CLOSED`);
  a super admin can reopen it.
* **Attachments**: `POST .../attachments/upload-url` returns a signed S3 `PUT` URL for exactly the declared type and size
  (PNG, JPEG, WebP, PDF, at most 5 MB). A message may then reference the key; the server checks that it was issued for
  this community, that the object exists, and its real type and size, and that no other message uses it. Messages carry a
  short-lived download URL.
* **Unread counters** are per side: what the other side wrote and this side has not read. Reading messages does **not**
  mark them read (a background poll must not count as seen); call `POST /threads/{id}/read` when the conversation is on
  screen (optionally `{"upToSeq": n}`). It is audited only when something actually changed.

### Polling (and how to swap it for SSE/WebSocket later)

Every message in a thread has a `seq` (1, 2, 3, ...), assigned under the thread's row lock, so numbers are gap-free and a
poller can never miss a message that committed late (a timestamp cursor could).

```
GET /community/support/threads/{id}/messages?after=<cursor>&limit=100
-> { "items": [ {seq, side, senderName, body, attachment, createdAt, readAt}, ... ],
     "cursor": 7, "hasMore": false, "thread": {status, priority, assignedTo, messageCount, lastMessageAt} }
```

Poll every 10 s with `after` = the cursor from the previous answer (start with `0`); if `hasMore` ask again at once.
For the thread list and badge, poll `GET /summary` instead: `unreadMessages`, `unreadThreads` and, per live thread,
`messageSeq`, `status`, `unreadCount`. A WebSocket/SSE push can send exactly these message objects, each with its `seq` as
the event id; clients resume with `after=<last seq seen>`, so the polling endpoint stays the catch-up path.

### Email

A new message emails the other side, but a burst sends **one** email: an email is queued when the first unread message
arrives, and again only when the recipient has read everything in between, or after
`app.support.email-reminder-minutes` (default 30) with messages still unread. Community admins are emailed when the
platform writes; the assigned super admin (or every active one when nobody is assigned) when a community writes. Support
mail is platform mail (`community_id` null) and never uses a community's email quota.

## Announcements

### Community announcements (`/community/announcements`)

* States: `DRAFT` → `SCHEDULED` → `SENT`. A sent announcement is final (`409 ANNOUNCEMENT_NOT_EDITABLE`).
* **Body is HTML, sanitised on the server** with an allow-list (OWASP Java HTML Sanitizer): paragraphs, line breaks,
  bold/italic/underline/strike, lists, headings, quotes, code and links (`http`, `https`, `mailto` only, always
  `rel="nofollow noopener noreferrer"`). Scripts and styles are removed with their content; images, iframes, forms, inline
  styles, classes and event handlers are dropped. Only the clean version is ever stored or sent; a body with nothing left
  after cleaning is rejected.
* Audience: `ALL_ACTIVE`, `GROUP` (group name, case-insensitive) or `SELECTED` (member ids). Only active members.
* `GET /{id}/preview`: the content as sent, audience size, who is eligible, who would be skipped (no address / no consent)
  and the quota left. Queues nothing. `POST /{id}/send-test` mails one copy to the signed-in admin (uses one email of the
  quota; works on a draft).
* `POST /{id}/send` with `sendEmail: true` queues one outbox email per member with an address and consent, in member
  number order, **up to the plan's monthly email quota**; the rest are recorded as skipped. The announcement is still
  published. The counts are kept on it: `recipientsTotal`, `emailsQueued`, `skippedNoEmail`, `skippedNoConsent`,
  `skippedQuota`. A second send (or two at once) is a `409`: members are never emailed twice.
* `scheduledAt` (future) makes it `SCHEDULED`. A ShedLock job runs every minute and sends what is due, once, in a
  transaction per announcement with the row locked (safe on several instances; a failing one is retried next run and does
  not hold up the others). Announcements of a suspended community wait until it is active again.

### Platform announcements (`/admin/announcements`)

* Same life cycle and sanitising. Targets: every active community (default) or `communityIds`. `kind` is `ANNOUNCEMENT`,
  `OFFER` or `MAINTENANCE`.
* `sendEmail` emails each distinct active community admin of the targeted active communities once (platform mail, no
  community quota). `banner` also shows it in the app until `expiresAt`.
* Community admins see these in their **notification area** (`/community/notifications`): a list with a per-admin read
  flag, `GET /summary` (unread badge plus banners still to show), `POST /{id}/read` (dismisses the banner for that admin)
  and `POST /read-all`. They see an announcement only if it is sent, not expired, addressed to their community, and sent
  after their community was created.

## Audit

Every mutating endpoint writes an audit entry: `COMPLAINT_*`, `SUPPORT_*`, `ANNOUNCEMENT_*`, `PLATFORM_ANNOUNCEMENT_*`,
`NOTIFICATION_READ(_ALL)`. Message and comment bodies are never copied into the audit log (only lengths and flags).
