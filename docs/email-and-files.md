# Email and file infrastructure

Used by every module. Services never send email or talk to S3 themselves: they put a row in the **outbox** (inside the same
database transaction as the business change) or go through the **file service**.

## Email

```
service ──(same transaction)──▶ email_outbox (PENDING)
                                    │  every 30 s, ShedLock: emailOutboxJob
                                    ▼
                  EmailSender: reserve batch ─▶ suppression check ─▶ render ─▶ SMTP (SES) ─▶ SENT / retry / FAILED
```

* **Never in the request thread.** Requests only insert outbox rows; `EmailSender` is called only by the scheduled worker.
* **Safe on several instances.** A run reserves a batch in one statement (`FOR UPDATE SKIP LOCKED`): it counts the attempt and
  pushes `next_attempt_at` out by `app.email.lease` (5 min), so other instances skip those rows and a worker that dies mid-send
  only delays them. ShedLock additionally keeps the job to one instance at a time.
* **Retries.** A temporary failure (connection, 4xx, timeout) is retried after 1, 2, 4, 8, 16 minutes (`backoff-base` doubling);
  the sixth failed attempt marks the mail `FAILED` with the error. A permanent failure (SMTP 5xx, rejected address, unknown
  template, missing data, suppressed address) is `FAILED` at once. A super admin can put a failed mail back with
  `POST /admin/email/outbox/{id}/retry` after fixing the cause; `GET /admin/email/outbox` and `/stats` show the queue.
* **Secrets in payloads.** Templates whose payload carries a token (reset, invitation, member invite, and the bill/reminder pay
  links) have the payload erased (`{"erased": true}`) as soon as the mail is sent or has failed for good.
* **Quota.** Mail queued for a community counts against the plan's `emails_per_month` and the optional `emails_per_day` (India
  calendar; failed mail is not counted); the tighter one applies. Platform mail (resets, invitations, support, leads, platform
  announcements) has no community and is never charged to one. `GET /community/email-usage` shows usage against the limits.
* **Suppression list.** `email_suppressions` holds addresses the app never emails again. The sender checks it for every mail.
  Hard bounces and spam complaints reported by SES are added automatically; super admins can add or remove addresses
  (`/admin/email/suppressions`). Temporary ("soft") bounces are ignored.

### SES bounces and complaints (SNS webhook)

`POST /api/v1/webhooks/ses` (public). In AWS: publish SES bounce and complaint events to an SNS topic and subscribe this URL
over HTTPS. Set `SES_SNS_TOPIC_ARNS` (comma separated) and, optionally, `SES_CONFIGURATION_SET` (sent as a header so events
reach the topic). The endpoint trusts nothing it has not verified:

1. the topic must be on the allow-list;
2. the signing certificate URL must be `https://sns.<region>.amazonaws.com/....pem` (no other host, port or credentials);
3. the signature (SHA1withRSA v1 / SHA256withRSA v2) must match the canonical string of the message fields;
4. a subscription confirmation is only fetched from an Amazon SNS host.

Anything else is a `403` with no detail. Bodies over 256 KB are refused.

### Templates

`MailTemplates` is the registry (name, audience, subject, required fields, sensitive). Each template is
`mail/NAME.html` (Thymeleaf, table layout, inline CSS, 600 px, responsive) and `mail/NAME.txt` (hand-written plain text).
Brand colours: deep teal `#07363E`, teal `#037077`, gold `#C9A227` (an accent line, never text on white).

| Audience | Header | Footer | Templates |
| --- | --- | --- | --- |
| MEMBER | community name and logo (embedded inline) | the community's contact details, address, "contact the community admin to stop these emails"; `Reply-To` and `List-Unsubscribe` point at the community; From shows "Community via Amanah Connect" | member-welcome, member-invite, member-registration-approved/-rejected, member-bill, payment-reminder, overdue-notice, member-receipt (PDF attached), member-announcement, complaint-update |
| ADMIN | Amanah Connect | platform footer | password-reset, invitation, two-factor-changed, member-registration-received, support-message, subscription-expiring/-expired, community-suspended/-activated, platform-announcement |
| PLATFORM | Amanah Connect | platform footer | lead-acknowledgement, lead-notification |

Payload values are HTML-escaped; links must be `http(s)`; announcement bodies are sanitised again at render time; subjects are
single-line. **Adding a template**: add it to `MailTemplates` and `MailSamples`, write `NAME.html` and `NAME.txt`, run
`./mvnw test -Dtest=MailTemplatesTest -DargLine=-Dgolden.update=true`, review the new files in `src/test/resources/golden/mail`.

**Preview (dev/staging only).** With `app.email.preview-enabled=true` (on in the `local` profile) a super admin can open
`GET /api/v1/admin/email/templates/{name}/preview?format=html|text` with sample data. In production it answers 404.
The golden-file test renders every template with the same sample data and fails when the output changes unexpectedly.

### Reminders

`invoiceReminderJob` runs at 09:00 India time, for every ACTIVE community, following its notification settings:

* **due soon**: an unpaid invoice due within `due_reminder_days_before` days gets one reminder (never on the day it was issued);
* **overdue**: an overdue invoice gets a notice the first day it is overdue and again every `overdue_reminder_every_days` days.

Only active members with an address who agreed to email are reminded, within the email quota, with a fresh payment link when UPI
is set up. It is idempotent per invoice and day: the reminder is claimed by inserting a unique `(invoice, kind, day)` row, and only
the run that inserted it queues the mail, so repeating the job or running it on two instances sends each reminder once. If the
mail is not allowed (no consent, no quota) the claim is released and a later run tries again.

### Local development

`docker compose -f docker-compose.local.yml up -d` starts Postgres, Mailpit and MinIO. With `SPRING_PROFILES_ACTIVE=local` the
worker sends through Mailpit (`localhost:1025`); open http://localhost:8025 to see the branded emails.

## Files

Everything that goes to object storage goes through `FileService`.

* **Upload**: the server builds the key `communities/{communityId}/{folder}/{uuid}.{ext}` (folders: `logo`, `ledger`, `support`,
  `receipts`), checks the content type against the kind's allow-list (PNG, JPEG, WebP, PDF; never SVG or HTML), the size against the
  kind's limit (logo 512 KB, attachments 5 MB) and the plan's storage allowance, and returns a presigned `PUT` that is signed for
  exactly that content type and length (the browser cannot send anything else), valid 10 minutes.
* **Accept** (after the upload): the key must be one issued for this community and kind; the object must exist; its stored type and
  size must be allowed and agree with the extension; its first bytes must really be that type (**magic bytes**: a renamed HTML or
  script file is refused); and it must fit in the plan's storage. A file that fails is deleted. Accepted files are recorded in
  `stored_files`.
* **Storage usage** is the sum of a community's recorded live files and is checked against the plan's `storage_mb` both when the
  upload is requested and when it is accepted (under a per-community lock, so two uploads cannot both take the last megabyte).
  Replacing or removing a file releases its bytes; receipt PDFs the server stores are recorded too.
* **Download**: short-lived presigned `GET` URLs; the bucket is never public.
* **Local**: MinIO (`S3_BUCKET`, `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`); production uses the EC2 instance role.

Files stored before this release (logos, ledger attachments) are not in `stored_files`, so they are not counted until replaced.
