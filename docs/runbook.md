# Runbook

For whoever is on call. Status of the deployment artefacts: the **Dockerfile, production Compose file and Nginx config belong to prompt D1/D2 and are not
in the repository yet**; the commands below describe the intended procedure and name the files they will use. Everything about the application itself
(health checks, configuration, migrations, metrics) is real and tested.

## Quick facts

| | |
|---|---|
| Public API | `https://<domain>/api/v1/...` (Nginx → API :8080) |
| Liveness / readiness | `GET /actuator/health/liveness`, `/actuator/health/readiness` on :8080 (status only). Readiness = app ready + database (+ S3 if `APP_HEALTH_STORAGE_ENABLED=true`) |
| Metrics | `http://localhost:8081/actuator/prometheus` (private port, never proxied) |
| Logs | stdout; JSON in `prod`, one object per line with `requestId`, `userHash`, `communityId` |
| Config | environment variables only (`.env.example` lists them); no secrets in the image or git |
| Database | Neon PostgreSQL. App → pooled endpoint (`DB_URL`), Flyway → direct endpoint (`FLYWAY_URL`) |
| Schedules | `Asia/Kolkata`: overdue marking 00:30, recurring invoices generated 06:00, reminders 09:00, subscription expiry 09:00; outbox every 30 s; exports every 30 s; announcements every minute |

## Deploy

1. CI is green on `main` (`Backend CI`: build, tests, coverage floor, dependency-check).
2. Build and tag the image: `docker build -t amanah-api:<git-sha> backend` (D1).
3. On the EC2 host: `docker compose pull` (or load the image), then `docker compose up -d api`. Compose starts the new container, **Flyway migrates on startup**
   (direct endpoint, advisory lock, so two instances cannot migrate at once), then the readiness probe turns UP.
4. Verify: `curl -fsS https://<domain>/actuator/health/readiness` → `{"status":"UP"}`; log in; open the dashboard; check `http_server_requests` 5xx in Prometheus for 10 minutes.
5. Graceful shutdown: on SIGTERM the server stops accepting connections and gives in-flight requests up to 30 s (`spring.lifecycle.timeout-per-shutdown-phase`).
   Set the container `stop_grace_period` to at least 40 s.

Migrations must be **backward compatible with the previous release** (add columns nullable or with defaults, add tables, never drop or rename in the same release
that stops using them) so a rollback of the application never meets a schema it cannot read.

## Roll back

* **Application only** (the usual case): `docker compose up -d api` with the previous image tag. Because migrations are backward compatible, the old version runs on the new schema.
* **A bad migration**: never edit an applied migration. Write a new `V<n+1>` migration that fixes forward. If the migration itself failed half way, Flyway marks it failed and
  the app will not start: fix the cause, run `flyway repair` against the **direct** endpoint, then redeploy. PostgreSQL DDL is transactional, so a failed migration normally leaves nothing behind.
* **Bad data** (a wrong bulk operation): do not restore the whole database for it. Financial rows are never deleted; corrections are reversal entries through the API
  (`/payments/{id}/reverse`, ledger reversal). Use the audit trail (`/admin/audit?communityId=...`) to see exactly what happened.

## Restore the database

Neon keeps history; restoring is a point-in-time branch, not an overwrite.

1. Decide the target time (just before the incident). Note the current time and what will be lost between the two.
2. Neon console → project → **Branches → Create branch → Point in time** (or "Restore" on the main branch if you accept in-place rollback). Name it `restore-<date>`.
3. Point a **staging** copy of the API at the branch (`DB_URL`/`FLYWAY_URL` of the new branch) and check the data is what you expect (login, dashboard, the community in question).
4. To switch production: update `DB_*`/`FLYWAY_*` secrets to the branch endpoints and redeploy (or, if you used in-place restore, nothing to change). Keep the old branch for 7 days.
5. After a restore: mail sent in the lost window has already gone out (the outbox rows are gone, the members have the emails); payments recorded in that window must be re-entered from
   the members' receipts. Tell the affected communities.
6. Logical backups (belt and braces): `pg_dump --format=custom --no-owner "$FLYWAY_URL_AS_LIBPQ" > amanah-$(date +%F).dump` nightly to a **separate** encrypted bucket; restore with `pg_restore --clean --if-exists --no-owner -d <target>`.
   Test a restore into an empty database every quarter and write down how long it took.

## Rotate secrets

| Secret | How | Effect |
|---|---|---|
| `JWT_SECRET` | Generate `openssl rand -base64 48`, update the secret, redeploy | Every access token becomes invalid; users sign in again (refresh tokens are not JWTs and keep working, so most sessions renew silently) |
| `TOTP_ENC_KEY` | **Do not rotate casually.** Changing it makes every enrolled 2FA secret unreadable. To rotate: add a re-encryption step (decrypt with the old key, encrypt with the new) in a one-off job *before* switching | Without that step every 2FA user is locked out and must use a recovery code |
| Database password | Change in Neon, update `DB_PASSWORD` and `FLYWAY_PASSWORD`, redeploy | Brief connection errors during the switch; Hikari reconnects |
| SMTP (SES) credentials | Create a new SMTP user in SES, update `MAIL_USER`/`MAIL_PASSWORD`, redeploy, delete the old user | Mail waits in the outbox and retries, nothing is lost |
| S3 keys | Prefer the EC2 instance role (no keys). If keys are used: new key, update `S3_*`, redeploy, delete the old key | Uploads/downloads fail until the redeploy |
| Super-admin password | Change it in the app (`POST /auth/password/change`); all refresh tokens are revoked | |
| Suspected leak of any of the above | Rotate it now, then `POST /auth/logout-all` for affected users, then read `/admin/audit` for the window | |

## Common incidents

**Readiness is DOWN / the app restarts in a loop.** `docker compose logs api | tail -100`. Usual causes: database unreachable (check Neon status and that `DB_URL` is the *pooled* host);
a migration failed (log says `Migration V… failed`; see "A bad migration"); a required secret is missing (`JWT_SECRET`, `TOTP_ENC_KEY`: startup refuses). At startup the app retries the
database for up to 60 s (Neon waking from suspend), so a short Neon cold start is not an incident.

**Slow responses.** Look at `http_server_requests_seconds` by URI, then `hikaricp_connections_pending` (> 0 for long = pool exhausted: a slow query or a connection leak; Hikari logs leaks
with the stack in the test profile) and the slow-query log (`org.hibernate.SQL_SLOW`, > 250 ms). Run `EXPLAIN (ANALYZE, BUFFERS)` on the statement. `docs/performance.md` has the reference numbers and how to
re-run the plan check.

**Email is not arriving.** `amanah_outbox_pending` and `amanah_outbox_oldest_pending_seconds`: if the oldest is > 10 minutes the sender is stuck. Check `amanah_email_processed_total{result="failed"}`,
the `email_outbox` rows with `status='FAILED'` (the `error` column says why), SES sandbox/sending limits, and that `APP_SCHEDULING_ENABLED=true` on exactly the instance that should run jobs. A
bounced address is auto-suppressed; remove it at `/admin/email/suppressions` once the member confirms a new address. Rows stuck as claimed (`next_attempt_at` in the future after a crash) are re-leased after 5 minutes.

**A community says its numbers are wrong.** Open `/admin/audit?communityId=<id>` and the community's own `/community/audit`. Invoices and payments are never edited; look for `PAYMENT_REVERSED`, `INVOICE_CANCELLED`.
The dashboard is cached for 30 s per community (`app.dashboard.cache-seconds`).

**Login failures spike.** `amanah_auth_attempts_total{type="login",result="invalid_credentials"}` and `result="locked"`. A steady stream from one IP is rate-limited (429) by itself; a spread-out stream is credential
stuffing: nothing to do beyond watching, accounts lock after 5 failures. A legitimate user locked out waits 15 minutes or uses "forgot password".

**Scheduled jobs did not run.** `amanah_job_duration_seconds_count{job=...}` flat. Check `shedlock` rows (`select * from shedlock`): a stale lock row older than its `lockAtMostFor` is ignored automatically. The app only
runs jobs when `APP_SCHEDULING_ENABLED=true`. Jobs are idempotent: after fixing, restart and they catch up (recurring invoices for the current period are generated once; overdue marking is a pure function of dates).

**Disk or storage full / S3 errors.** Uploads answer 503 `STORAGE_UNAVAILABLE`; the rest of the app keeps working. Check the bucket policy, credentials and `app.storage.bucket`. The optional readiness check
(`APP_HEALTH_STORAGE_ENABLED=true`) takes the instance out of the load balancer instead; use it only if you run more than one instance.

**A data export is stuck.** `select id,status,started_at,error from data_exports order by created_at desc limit 10;` A `RUNNING` row older than 30 minutes is re-claimed automatically. `FAILED` rows carry the error; the
community can request another (3 per day). Exports (ZIPs) are deleted from S3 when their 48-hour link expires.

**Someone asks for their data to be erased.** The community admin does it: `POST /community/members/{id}/anonymise` (member number typed back as confirmation). Platform staff cannot do it on their behalf through the API; if
a community is unreachable, do it in a reviewed, audited maintenance script that calls the same service.

## Routine checks

* Weekly: Dependabot PRs, dependency-check report, `amanah_outbox_failed_24h`.
* Monthly: restore test of the logical backup into an empty database; look at the slowest endpoints in Prometheus.
* Quarterly: rotate JWT secret and SMTP credentials; review super-admin accounts; re-run `PerformanceIT` (see `docs/performance.md`) if data volumes have grown 3x.
