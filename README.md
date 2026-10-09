# Amanah Connect

Multi-tenant community management SaaS. Members have no login; everything reaches them by email.
Read [`CLAUDE.md`](CLAUDE.md) (hard rules) and the
[blueprint](AMANAH_CONNECT_V2_PRODUCTION_BLUEPRINT.md) before changing anything.

```
backend/    Spring Boot 4.1 API (Java 21, Maven, PostgreSQL, Flyway)
frontend/   React 19 + Vite + TypeScript (scaffold only for now)
docker-compose.local.yml   Postgres 16 + Mailpit + MinIO for local development
.env.example               every environment variable the backend reads
```

## Prerequisites

Java 21, Docker (local services and Testcontainers), Node 22+ (frontend). Maven is provided by the
wrapper (`./mvnw`).

## Run the backend locally

```bash
docker compose -f docker-compose.local.yml up -d     # Postgres :5432, Mailpit SMTP :1025 + UI :8025, MinIO :9000 (console :9001)
cd backend
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

| Check | Command | Expect |
|---|---|---|
| Ping | `curl localhost:8080/api/v1/ping` | `{"version":"…","time":"…"}` |
| Readiness | `curl localhost:8080/actuator/health/readiness` | `{"status":"UP"}` |
| Liveness | `curl localhost:8080/actuator/health/liveness` | `{"status":"UP"}` |
| Prometheus (private port) | `curl localhost:8081/actuator/prometheus` | metrics |
| Swagger UI (local profile only) | http://localhost:8080/swagger-ui.html | UI |
| Caught emails | http://localhost:8025 | Mailpit |

Flyway runs at startup and applies `V1__init_extensions.sql` (citext, pgcrypto).

`local` is the default profile (set in `application.yml`). **Production must set
`SPRING_PROFILES_ACTIVE=prod`**; if it is forgotten the app would start with local settings
(Swagger on, throwaway auth keys). Remove `spring.profiles.active` from `application.yml` if you
prefer a missing profile to fail at startup instead.

## Test

```bash
cd backend && ./mvnw verify      # unit tests (*Test), then integration tests (*IT)
```

Integration tests start a throwaway Postgres 16 with Testcontainers, so Docker must be running.

## Profiles and configuration

| Profile | Database | Mail | Logs | API docs |
|---|---|---|---|---|
| `local` | `docker-compose.local.yml` (overridable with env vars) | Mailpit | readable | on |
| `test` | Testcontainers | none | readable | off |
| `prod` | env vars only, no defaults | env vars | JSON (logstash encoder) | off |

`prod` has no fallbacks: a missing variable aborts startup, including `JWT_SECRET` and `TOTP_ENC_KEY`. See [`.env.example`](.env.example)
for the full list. Config is read from the process environment (Spring does not read `.env`
files; use `set -a; source .env; set +a` in your shell).

### Ports and actuator

* **8080** public API. Also serves status-only `/actuator/health`, `/actuator/health/liveness`,
  `/actuator/health/readiness` (no component details).
* **8081** management port with `health`, `info`, `prometheus`. It must never be published or
  proxied to the internet (bind it to the Docker network / security group only).

Everything else on 8080 is deny-by-default (401 problem+json).

### Request ids

Every response carries `X-Request-Id`. A caller-supplied value is reused only if it is 1–64 chars
of `[A-Za-z0-9._-]`, otherwise a UUID is generated. The id is in the log MDC (`requestId`) and in
every problem+json body.

## Authentication

JWT access tokens (15 min, `Authorization: Bearer`), rotating refresh tokens in an HttpOnly cookie,
TOTP 2FA with recovery codes, lockout, rate limiting and an audit trail. Design notes, the full flow
and the known limits are in [`docs/auth.md`](docs/auth.md).

**First super admin.** On the very first start set `BOOTSTRAP_SUPERADMIN_EMAIL` and
`BOOTSTRAP_SUPERADMIN_PASSWORD` (the password must meet the policy: 10+ characters, mixed classes or a
long passphrase). The account is created only if no SUPER_ADMIN exists and must enrol in 2FA at its
first login. **Remove both variables afterwards.**

**Try it against a running app:**

```bash
ADMIN_EMAIL=owner@example.test ADMIN_PASSWORD='...' ./docs/auth-smoke.sh
```

It logs in, refreshes (showing rotation and reuse detection), enrols 2FA, completes a 2FA login and
logs out. It needs curl and python3, and it enables 2FA on the account it uses.

## Multi-tenancy and shared foundations

Tenant isolation, auditing, paging, money handling and plan limits are described in
[`docs/tenancy.md`](docs/tenancy.md), including the checklist every new `/community` module must follow.
Integration tests of tenant modules extend `AbstractTenantIT`.

## Platform administration (SUPER_ADMIN)

Everything under `/api/v1/admin/**` is for SUPER_ADMIN accounts with 2FA completed: communities (create,
suspend, archive, export, support overview, CSV import for migrations), plans, platform subscriptions and
their daily expiry job, demo-request leads and platform statistics. The public demo-request form is
`POST /api/v1/public/leads`. Details, decisions and limits are in [`docs/admin.md`](docs/admin.md).
Run with the `local` profile and open `/swagger-ui.html` to browse the API grouped by area.

## Community administration (COMMUNITY_ADMIN)

Under `/api/v1/community/**`: settings (profile, logo, UPI, currency, financial year, notification
preferences), members (search, CRUD, activate/deactivate, soft delete, CSV import and streamed export,
dashboard counts) and invite links with self-registration review. The public side of invite links is
`GET /api/v1/public/invites/{token}` and `POST /api/v1/public/invites/{token}/register`. Rules, decisions
and limits are in [`docs/members.md`](docs/members.md).

Read side: `GET /community/dashboard` (cached 30 s), the audit trail (`/community/audit`, and
`/admin/audit` with CSV export for platform staff), community data export as a ZIP of CSVs by email
link, and member erasure. See [`docs/read-side.md`](docs/read-side.md).

## Testing against a Neon database

Neon is PostgreSQL. You need **two** hostnames from the Neon console for the same database:

* **pooled** host, contains `-pooler`, for the application (`DB_URL`)
* **direct** host, the same name without `-pooler`, for Flyway (`FLYWAY_URL`)

Flyway uses advisory locks, which do not survive PgBouncer transaction pooling, so migrations
must use the direct endpoint.

1. In the Neon console choose the database role and copy the connection string, once with
   *Connection pooling* on (pooled) and once off (direct). They look like
   `postgresql://USER:PASSWORD@HOST/DB?sslmode=require&channel_binding=require`.
2. Translate to JDBC. JDBC does **not** accept `user:password@` in the URL and does not support
   `channel_binding`, so split it:
   * `DB_URL=jdbc:postgresql://<pooled-host>/<db>?sslmode=require`
   * `FLYWAY_URL=jdbc:postgresql://<direct-host>/<db>?sslmode=require`
   * `DB_USER` / `DB_PASSWORD`, and `FLYWAY_USER` / `FLYWAY_PASSWORD` (same role is fine)
3. Boot the app. Don't put these in a committed file. Export them in your shell:

   ```bash
   cd backend
   export DB_URL='jdbc:postgresql://<pooled-host>/<db>?sslmode=require'
   export DB_USER='…' DB_PASSWORD='…'
   export FLYWAY_URL='jdbc:postgresql://<direct-host>/<db>?sslmode=require'
   export FLYWAY_USER="$DB_USER" FLYWAY_PASSWORD="$DB_PASSWORD"
   SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
   ```

   The `local` profile is the quickest way to try Neon: it reads the same `DB_*` / `FLYWAY_*`
   variables, only defaulting them to the local Docker database when they are unset. To
   exercise the real production wiring, use `SPRING_PROFILES_ACTIVE=prod` and also export
   `MAIL_HOST`, `MAIL_USER`, `MAIL_PASSWORD` (placeholders are fine for this test),
   `ALLOWED_ORIGIN`, `FRONTEND_BASE_URL`, `JWT_SECRET` and `TOTP_ENC_KEY`.
4. Verify:
   * `curl localhost:8080/actuator/health/readiness` returns `{"status":"UP"}` (this runs a real
     query through the pooled connection)
   * the log shows Flyway connecting to the **direct** host and applying `V1`
   * in the Neon SQL editor: `select version, success from flyway_schema_history;` shows `1 | t`

Troubleshooting:

* *First start is slow or Flyway retries*: the Neon compute is waking from suspend. Flyway
  retries 3 times; Hikari waits up to 10 s.
* *`prepared statement "S_1" already exists`* on the pooled endpoint: add `&prepareThreshold=0` to
  `DB_URL` only.
* *`Connection … has been closed` after idle time*: expected on Neon; Hikari recycles
  connections every 25 minutes, before Neon drops them.
* The password you used in any chat or ticket is compromised. Rotate it in Neon.

## CI

`.github/workflows/backend-ci.yml` runs `./mvnw verify` on pull requests that touch `backend/`.
