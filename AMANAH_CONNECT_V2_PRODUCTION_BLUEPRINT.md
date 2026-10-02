# AMANAH CONNECT — V2 PRODUCTION BLUEPRINT
> Spring Boot 3 + React 19 + PostgreSQL (Neon) + AWS EC2
> Multi-tenant SaaS for communities of any kind. A community is identified by its name only (no community types)
> Built for live users. This replaces the earlier FastAPI master doc.

---

## 0. READ THIS FIRST — corrections and decisions

### 0.1 "MySQL Neon" — Neon is PostgreSQL, not MySQL
Neon only offers **PostgreSQL**. There is no MySQL on Neon. You already have a Neon database, so this blueprint uses **PostgreSQL on Neon**. Spring Boot + JPA + Flyway work with it perfectly. If the client insists on MySQL, the alternative is AWS RDS MySQL, and it changes cost and ops. Confirm with the client, but Postgres on Neon is the recommended path.

### 0.2 Things I got wrong earlier (fixing now)
1. **The seed SuperAdmin in the old SQL script is not safe to use.** I wrote a bcrypt hash into it that I never verified against `Admin@123`. Do not run that old script in production. In V2 the first SuperAdmin is created by a one-time bootstrap command using an environment variable, and the account is forced to set up 2FA on first login.
2. **"Community Help Desk (Chat)" in the Excel is a chat between Community Admin and SuperAdmin** (support). Earlier I treated it as a complaints list. V2 has both: (a) a support chat/ticket thread (Community Admin to SuperAdmin) and (b) Complaint Management inside a community (members' complaints, logged by the admin).
3. **"Invite Management" with no member login** is solved this way: the admin sends an invite link or QR, the person fills a public self-registration form (no password, no account), and the admin approves them. For L1, invites are for onboarding new Community Admins.
4. **Neon + Spring Boot connection string** is different from what I showed for Python. JDBC needs `jdbc:postgresql://host/db?sslmode=require` with username and password as **separate** properties. Details are in Prompt B0.
5. **Rotate your Neon password.** The old one was pasted in this chat, so treat it as compromised.

### 0.3 Decisions I made (change any you disagree with)
| Topic | Decision | Why |
|---|---|---|
| Payments | No payment gateway in v1. **UPI deep-link QR** (real, scannable) + manual "Mark as Paid" | Works in India with zero gateway fees, still matches "fake payment for now". Gateway is a clean interface for later |
| Data visibility | SuperAdmin gets **read-only** access to community data, every access is audit-logged | Trust and transparency is the product promise |
| Multi-tenancy | One database, shared schema, `community_id` on every tenant table, enforced in code and tested | Simple, cheap, safe if tested |
| Files | Private S3 bucket, presigned URLs | Never serve receipts from the server disk |
| Email | Amazon SES | Cheap, reliable, same AWS account |
| Hosting | Single EC2 behind Nginx with Docker Compose | Matches your ask. Honest limit: single point of failure (see section 9) |
| Brand | Colours taken from your logo | See section 2 |

### 0.4 Open questions for the client (get answers before Phase 2)
1. Currency and country: INR only, or multi-currency? (V2 assumes INR, built so currency is a field.)
2. Languages: English only, or also Hindi/Urdu/Arabic (RTL)? Affects frontend structure now, cheap to prepare.
3. Domain name and sending email domain (needed for SES: SPF, DKIM, DMARC).
4. Who pays whom and how: confirm plan pricing, yearly discounts, GST invoices for the platform fee.
5. Data protection: India's DPDP Act 2023 applies to member personal data. Client should decide retention period and deletion process. I am not a lawyer, so get this checked.
6. Is a mobile app expected later? (Web is responsive PWA in v1.)
7. Events module: the About Us text mentions events. The Excel does not. V2 puts it in Phase 2.

---

## 1. PRODUCT SCOPE

### 1.1 What it is
A platform where **any community** gets a private workspace to manage members, dues and donations, budget, complaints and announcements, with transparent finances and every bill, receipt and update delivered by email. Members never log in. Community Admins and the platform SuperAdmin do.

### 1.2 One generic community model (no community types)
A community is identified **only by its name** (plus contact details). There is no "type" field anywhere: not in the database, the API, the forms or the filters. Every community gets the same features and the same defaults, which keeps the product simple and avoids type-specific code paths.

Flexibility comes from settings the admin controls, not from a type:
- A short default set of budget categories (Maintenance, Utilities, Repairs, Events, Donations, Administration, Other) that the admin can add to, rename or hide.
- An optional **member group label** that the admin names themselves (for example "Flat", "Family", "Batch" or "Team"), so the same field works for any community.
- The fee kind on an invoice (Maintenance, Subscription, Donation, Event, Fine, Other) is chosen per invoice.

### 1.3 Roles
| Role | Who | Access |
|---|---|---|
| `SUPER_ADMIN` | Platform owner (client) | All communities, plans, subscriptions, audit, leads, support chat. Community data read-only |
| `COMMUNITY_ADMIN` | Head of one community | Full control of own community only |
| Member | Resident, congregant, volunteer | **No login.** Email only |

Phase 2 option: multiple admins per community (Treasurer, Secretary) with sub-roles. The schema supports it from day one via `community_users`.

### 1.4 Excel requirements mapped to modules
| Excel item | Module | Phase |
|---|---|---|
| Landing page | Public site + demo request leads | 1 |
| L1: Overview of all communities (name, owner, contact, DOE) | Communities | 1 |
| L1: Automate email | Notification engine + templates + schedules | 1 |
| L1: Plans (restrictions) | Plans + limit enforcement | 1 |
| L1: Invite management | Admin invitations | 1 |
| L1: Community help desk (chat) | Support threads | 1 |
| L1: Reset password / export details | Admin tools + data export | 1 |
| L1: Make community (migration or new) | Onboarding wizard + CSV import | 1 |
| L1: Announcements (emails and offers) | Platform broadcasts | 1 |
| L1: Audit logs (export) | Audit | 1 |
| L1: Two-factor authentication | TOTP 2FA | 1 |
| L2: User management (active users, create, act/deact) | Members | 1 |
| L2: Budget management | Ledger + reports | 1 |
| L2: Auto email | Reminders, receipts, welcome | 1 |
| L2: Complaint management | Complaints | 1 |
| L2: Invite management | Member invite links + self-registration | 1 |
| L2: QR or link for payment | UPI QR + payment link page | 1 |
| Events, polls, documents vault | Community extras | 2 |

---

## 2. BRAND AND DESIGN SYSTEM (from your logo)

The logo: deep teal "AC" monogram with a flowing wave, a gold crescent hugging a community icon and an open hand (care, trust), serif wordmark AMANAH in deep teal with CONNECT in gold, tagline "Connecting Communities with Trust", and four values: **Trust, Integrity, Collaboration, Excellence**. Mood: dignified, warm, trustworthy, quietly premium. Not "startup neon".

### 2.1 Colour tokens (sampled from the logo, approximated)
Confirm exact values with the client's brand guideline if one exists.

| Token | Hex | Use |
|---|---|---|
| `brand-950` | `#04262C` | Footer, deepest surfaces |
| `brand-900` | `#07363E` | Sidebar, hero, headings (the logo's deep teal) |
| `brand-800` | `#0A4A52` | Hover on dark |
| `brand-600` | `#037077` | **Primary** buttons and links |
| `brand-500` | `#038D8F` | Wave accent, charts, focus ring |
| `brand-100` | `#D6EFEF` | Soft fills |
| `brand-50` | `#EEF8F8` | Page tint |
| `gold-500` | `#C9A227` | Decorative accents, borders, icons, highlights |
| `gold-600` | `#8A6A12` | **Gold text on light backgrounds** (about 5:1 contrast) |
| `gold-200` | `#F0E2A8` | Badges |
| `gold-50` | `#FBF6E3` | Warm highlight bands |
| `ivory` | `#FAFAF7` | App background |
| `ink` | `#0F2A2E` | Body text |
| `muted` | `#5B6F72` | Secondary text |
| `success` | `#3A8C10` | Paid, active (the handshake green) |
| `premium` | `#5B2C91` | Enterprise plan, special badges (the star purple) |
| `danger` / `warning` | `#B42318` / `#B54708` | Errors, overdue |

**Accessibility rule:** `gold-500` on white fails contrast for text. Use gold only for borders, icons, large display type on dark backgrounds, and decoration. Use `gold-600` for any gold text on light backgrounds. Target WCAG 2.2 AA everywhere.

### 2.2 Typography
- **Wordmark and section eyebrows:** Cinzel (matches the logo's wide classical caps), letter-spaced.
- **Headings:** Playfair Display (600/700).
- **Body and UI:** Inter (400/500/600), tabular numbers for money.
- Money always right-aligned, tabular, `₹1,23,456.00` (Indian grouping via `Intl.NumberFormat('en-IN')`).

### 2.3 Visual language
- Rounded 12 to 16px cards, soft teal-tinted shadows, thin gold hairline dividers.
- Subtle geometric pattern (the faint Islamic-geometry lattice visible in the logo background corners) at 4 to 6% opacity on hero and footer. Keep it tasteful and culturally neutral enough for all communities.
- The curved wave from the logo reused as hero section divider and as the sidebar active-item indicator.
- Motion: 150 to 250ms ease-out, `prefers-reduced-motion` respected. No parallax gimmicks.
- Icons: lucide-react only. Charts: Recharts in brand palette.

### 2.4 Logo assets I prepared
In `brand/`: `logo-original.jpg`, `logo-lockup-transparent.png` and `logo-mark-transparent.png` (for **light** backgrounds), plus the plain crops. Limits: these are raster crops, not vectors. **Ask the client for the SVG or a high-res PNG, including a reversed (white and gold) version for dark backgrounds.** Until then, use the transparent PNGs on light surfaces only, and a text wordmark (Cinzel) on dark surfaces.

---

## 3. ARCHITECTURE

```
                    Internet
                       |
                 Route 53 / DNS
                       |
              Elastic IP -> EC2 (Ubuntu 24.04)
        +---------------------------------------+
        |  Nginx (TLS via Let's Encrypt, HSTS,  |
        |  gzip/brotli, security headers,       |
        |  rate limit, serves React build)      |
        |      |  /api/*  -> localhost:8080     |
        |  Spring Boot (Docker, Java 21)        |
        |  CloudWatch agent / log shipping      |
        +-------|------------|-------------|----+
                |            |             |
           Neon Postgres   S3 (private)   SES (email)
           (us-east-1,     receipts,      transactional
            pooled)        logos, exports  + bulk
                |
          Neon PITR backups  + nightly pg_dump -> S3
```

Neon's endpoint in your URL is `us-east-1`, so **put the EC2 in us-east-1** to keep DB latency low.

### 3.1 Stack
| Layer | Choice |
|---|---|
| Language/runtime | Java 21 LTS (Temurin) |
| Framework | Spring Boot (latest stable 3.5.x or 4.x; Claude Code must verify on start.spring.io) |
| Build | Maven |
| API style | REST + JSON, OpenAPI via springdoc |
| Persistence | Spring Data JPA (Hibernate), HikariCP |
| Migrations | Flyway (SQL files only, `ddl-auto=validate`) |
| DB | PostgreSQL 16+ on Neon |
| Security | Spring Security 6, stateless JWT access token, rotating opaque refresh token |
| 2FA | TOTP (RFC 6238) with recovery codes |
| Validation | Jakarta Validation |
| Mapping | MapStruct |
| Rate limiting | Bucket4j (in-memory v1) |
| Jobs | Spring `@Scheduled` + ShedLock (JDBC) |
| Email | Spring Mail to SES SMTP + Thymeleaf HTML templates |
| PDF | OpenPDF (receipts, reports) |
| QR | ZXing |
| Storage | AWS SDK v2 S3 |
| Config/secrets | env vars populated from AWS SSM Parameter Store |
| Observability | Actuator, Micrometer, JSON logs with request ID |
| Tests | JUnit 5, Mockito, Testcontainers (Postgres), Spring Security Test, ArchUnit |
| Frontend | React 19, Vite, TypeScript strict, Tailwind 3.4, Radix primitives (shadcn/ui pattern), React Hook Form + Zod, TanStack Query + Table, Zustand (auth only), React Router 6/7, Recharts, lucide-react, Sonner toasts, date-fns |
| FE tests | Vitest, React Testing Library, Playwright e2e, axe accessibility checks |
| CI/CD | GitHub Actions -> GHCR/ECR -> deploy to EC2 |

### 3.2 Backend package layout (modular monolith, package-by-feature)
```
backend/src/main/java/com/amanahconnect/
  AmanahConnectApplication.java
  common/        error model, pagination, money, ids, clock, web filters
  config/        security, cors, async, scheduling, s3, mail, openapi
  auth/          login, refresh, 2fa, password reset, invitations, lockout
  tenant/        TenantContext, tenant guard, plan limit checks
  community/     communities, onboarding, community_users, settings
  plan/          plans, subscriptions (platform billing), enforcement
  member/        members, groups, invites, self-registration, import/export
  billing/       fee plans, invoices, payment records, receipts, UPI QR
  ledger/        budget transactions, categories, summaries, reports
  complaint/     complaints and comments
  support/       helpdesk threads/messages (admin <-> superadmin)
  announcement/  community and platform announcements
  notification/  email outbox, templates, schedules, preferences
  audit/         append-only audit log + export
  file/          S3 service, presigned URLs, validation
  lead/          public demo requests
  report/        CSV and PDF exports
  publicapi/     unauthenticated endpoints (plans, contact, invite page)
```

### 3.3 Multi-tenancy rules (non-negotiable)
1. Every tenant table has `community_id UUID NOT NULL` with an index.
2. A `TenantContext` is populated from the **authenticated principal**, never from a request parameter or body.
3. Every repository method for tenant data takes `communityId` explicitly. No `findById(id)` alone on tenant tables.
4. Hibernate `@Filter` as a second safety net, plus integration tests proving admin A gets **404** (not 403) for community B's resources on every endpoint.
5. SuperAdmin access to community data goes through a separate read-only service that writes an audit entry.

### 3.4 Money rules
- `NUMERIC(14,2)` in DB, `BigDecimal` in Java, string in JSON. Never `double`.
- Currency stored per community (default `INR`).
- Receipts have **gap-free sequential numbers per community per financial year** (e.g. `AC-2026-27/000123`) from a locked counter row.
- Financial rows are never hard-deleted. Corrections are reversals (a negative entry with a reason), and everything is audited.

### 3.5 Domain tables (Flyway will create these; Claude Code writes the SQL in Prompt B1)
| Table | Key points |
|---|---|
| `users` | email unique (citext), password_hash, role, status, totp_secret_enc, totp_enabled, failed_attempts, locked_until, last_login_at |
| `refresh_tokens` | user_id, token_hash, family_id, expires_at, revoked_at, replaced_by, ip, user_agent |
| `recovery_codes` | user_id, code_hash, used_at |
| `communities` | name, slug unique, owner_user_id, contact fields, address, date_of_establishment, status (PENDING/ACTIVE/SUSPENDED/ARCHIVED), plan_id, currency, upi_id, upi_payee_name, logo_key, financial_year_start_month, settings jsonb |
| `community_users` | community_id, user_id, role (OWNER/ADMIN, future TREASURER) |
| `plans` | code, name, price_monthly, price_yearly, limits jsonb (max_members, storage_mb, emails_per_month), features jsonb, is_public, active, sort_order |
| `platform_subscriptions` | community_id, plan_id, period_start/end, amount, reference, status, recorded_by |
| `members` | community_id, member_no (unique per community), full_name, email, phone, group_label, status, joined_on, custom_fields jsonb, consent_email boolean, deleted_at |
| `member_invites` | community_id, token_hash, expires_at, max_uses, used_count, created_by |
| `member_registrations` | community_id, invite_id, submitted data, status (PENDING/APPROVED/REJECTED) |
| `fee_plans` | community_id, name, kind, amount, frequency (ONE_TIME/MONTHLY/QUARTERLY/YEARLY), due_day, applies_to (ALL_ACTIVE/GROUP/SELECTED), active |
| `invoices` | community_id, member_id, invoice_no, kind, period, amount, amount_paid, due_date, status (DRAFT/ISSUED/PARTIAL/PAID/OVERDUE/CANCELLED), fee_plan_id, version |
| `payment_records` | community_id, invoice_id nullable, member_id, amount, method (CASH/UPI/BANK/CHEQUE/OTHER), reference, received_on, recorded_by, receipt_id, reversed_of |
| `receipts` | community_id, receipt_no, payment_record_id, pdf_key, emailed_at |
| `ledger_categories` | community_id, name, type (INCOME/EXPENSE), active |
| `ledger_entries` | community_id, type, category_id, amount, entry_date, title, notes, attachment_key, source (MANUAL/PAYMENT), source_id, reversed_of, created_by |
| `complaints` | community_id, member_id nullable, subject, description, status, priority, assigned_to, resolved_at |
| `complaint_comments` | complaint_id, author_user_id, body, internal boolean |
| `support_threads` | community_id, subject, status (OPEN/WAITING/RESOLVED/CLOSED), priority, last_message_at |
| `support_messages` | thread_id, sender_user_id, body, attachment_key, read_at |
| `announcements` | community_id nullable (null = platform-wide), title, body, audience, send_email, status (DRAFT/SCHEDULED/SENT), scheduled_at, sent_at, created_by |
| `email_outbox` | community_id, to_email, template, payload jsonb, status (PENDING/SENT/FAILED), attempts, next_attempt_at, ses_message_id, error |
| `notification_settings` | community_id, due_reminder_days_before, overdue_reminder_every_days, send_welcome, send_receipt |
| `audit_logs` | actor_user_id, community_id, action, entity_type, entity_id, before jsonb, after jsonb, ip, user_agent, request_id, created_at. **Append-only** (DB trigger blocks UPDATE/DELETE) |
| `leads` | name, email, phone, community_name, size_estimate, message, status, handled_by, source |
| `shedlock` | ShedLock table |

### 3.6 API surface (prefix `/api/v1`)
```
PUBLIC     GET  /public/plans                    POST /public/leads
           GET  /public/invites/{token}          POST /public/invites/{token}/register
           GET  /public/pay/{token}              (payment info page, no login)
AUTH       POST /auth/login  /auth/login/2fa  /auth/refresh  /auth/logout
           POST /auth/password/forgot  /auth/password/reset  /auth/accept-invite
           POST /auth/2fa/setup  /auth/2fa/enable  /auth/2fa/disable  GET /auth/me
SUPERADMIN GET/POST/PATCH /admin/communities   POST /admin/communities/{id}/suspend|activate
           POST /admin/communities/{id}/reset-admin-password   GET .../export
           POST /admin/communities/import (migration)          GET /admin/communities/{id}/overview (read-only, audited)
           CRUD /admin/plans    GET/POST /admin/subscriptions  GET /admin/subscriptions/expiring
           GET/POST /admin/invitations        GET/PATCH /admin/leads
           GET/POST /admin/support/threads    POST /admin/announcements
           GET /admin/audit  GET /admin/audit/export          GET /admin/stats
COMMUNITY  GET /community/dashboard
           CRUD /community/members  PATCH .../status  POST /community/members/import  GET .../export
           CRUD /community/member-invites  GET/POST /community/registrations/{id}/approve|reject
           CRUD /community/fee-plans  POST /community/invoices/generate  GET/POST /community/invoices
           POST /community/invoices/{id}/payments  POST /community/payments/{id}/reverse
           GET  /community/invoices/{id}/qr  GET /community/receipts/{id}/pdf  POST .../resend
           CRUD /community/ledger/entries  /community/ledger/categories  GET /community/ledger/summary
           GET  /community/reports/{type}?format=csv|pdf
           CRUD /community/complaints  POST .../comments
           GET/POST /community/support/threads  POST .../messages
           CRUD /community/announcements
           GET/PATCH /community/settings  /community/settings/notifications
```
Error format is RFC 9457 `application/problem+json` with stable `code` values (`PLAN_LIMIT_EXCEEDED`, `VALIDATION_FAILED`, `NOT_FOUND`, `ACCOUNT_LOCKED`...).

---

## 4. SECURITY BASELINE (what "production grade" means here)

**Authentication**
- BCrypt cost 12. Password policy: min 10 chars, breached-password check optional (k-anonymity HIBP).
- Access JWT 15 minutes. Refresh token: 256-bit random, stored as SHA-256 hash, 7-day sliding, **rotated on every use with reuse detection** (reuse revokes the whole token family).
- Refresh cookie: `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. The refresh endpoint also requires a custom header and checks `Origin`.
- Account lockout: 5 failed logins gives a 15-minute lock. Generic error messages (no user enumeration). IP rate limit 10/min on login, 5/hour on password reset.
- 2FA mandatory for SuperAdmin, optional for Community Admin (SuperAdmin can require it per community). TOTP secret **encrypted at rest** (AES-GCM, key from SSM). 10 single-use recovery codes, hashed.
- All password reset, invite and verification tokens: single use, hashed in DB, short expiry.

**Authorization**: method-level `@PreAuthorize`, tenant guard, deny by default.

**Transport and headers**: TLS 1.2+, HSTS, CSP (no inline scripts), `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`, strict CORS to the app origin only.

**Data**: parameterized queries only, request size limits, file uploads validated by size, extension **and** magic bytes, stored in private S3, antivirus optional later. PII never in logs. Backups encrypted.

**Audit**: every create, update, delete, login, export and SuperAdmin data access.

**Dependency hygiene**: Dependabot, OWASP dependency-check, `npm audit` in CI.

---

## 5. HOW TO USE THE PROMPTS WITH CLAUDE CODE

1. Create the repo, put `CLAUDE.md` (section 6) in the root, and copy the brand folder to `frontend/public/brand/`.
2. Run prompts **in order**. Do not start the next until the "Done when" checks pass.
3. After each prompt: `git add -A && git commit`. Small commits make bad generations easy to undo.
4. If Claude Code goes off track, correct it in the same session. If the session gets long, start a new one with: *"Read CLAUDE.md and the repo, summarise the current state in 10 lines, then continue with Prompt X."*
5. Prompts ending with "Done when" are your acceptance tests. Make Claude Code run them and show output.
6. Never paste real secrets into prompts or commit `.env`. Use `.env.example`.

Suggested pace for a solo freelancer (honest estimate, it depends on how many fixes you do): backend 3 to 4 weeks, frontend 3 to 4 weeks, deployment and hardening 1 week, UAT with the client 1 to 2 weeks.

---

## 6. CLAUDE.md (put in repo root)

```
# CLAUDE.md — Amanah Connect

## What this is
Multi-tenant community management SaaS for any kind of community. Roles: SUPER_ADMIN, COMMUNITY_ADMIN. Members have NO login; they get everything by email. Production system with live users: correctness, security and auditability beat speed.

## Stack
- backend/: Java 21, Spring Boot (latest stable), Maven, Spring Security (JWT + rotating refresh), Spring Data JPA, Flyway, PostgreSQL (Neon), Testcontainers
- frontend/: React 19, Vite, TypeScript strict, Tailwind 3.4, Radix/shadcn pattern, React Hook Form + Zod, TanStack Query/Table, Zustand (auth only)
- Deploy: Docker Compose on AWS EC2 behind Nginx, S3, SES, GitHub Actions

## Hard rules
- Never use double/float for money. BigDecimal in Java, NUMERIC(14,2) in DB, string in JSON.
- Every tenant table has community_id. Tenant is taken from the authenticated principal only, never from request input. Repositories for tenant data always take communityId.
- Cross-tenant access returns 404, and every endpoint has a test for it.
- Schema changes only via new Flyway migrations (never edit applied ones). ddl-auto=validate.
- Financial rows are never hard-deleted; use reversal entries. Audit logs are append-only.
- Every mutating endpoint writes an audit log.
- No secrets in code, logs, or git. Config via env vars. Mask PII in logs.
- DTOs for all API I/O; never expose JPA entities. Validate all input.
- Errors are RFC 9457 problem+json with stable codes.
- Frontend: .tsx/.ts only, no `any`, no localStorage for tokens (access token in memory, refresh in httpOnly cookie).
- Frontend: use design tokens from tailwind.config.js (brand/gold/ivory). Gold text on light backgrounds must use gold-600.
- Every page has loading, empty, error states; keyboard accessible; WCAG AA.
- Write tests with the feature. A prompt is not done until `./mvnw verify` / `npm run build && npm test` pass.

## Working style
- Before large changes, state the plan in a few lines.
- Prefer small, reviewable commits.
- If a requirement is ambiguous, ask instead of guessing.
```

---

# 7. THE PROMPTS

## PHASE B — BACKEND (Spring Boot)

### PROMPT B0 — Project skeleton, local environment, Neon connection

```
Create the monorepo "amanah-connect" with backend/ and frontend/ folders, a root README.md, .gitignore, .editorconfig and .env.example. Read CLAUDE.md first.

BACKEND (backend/):
- Spring Boot, latest stable release (check start.spring.io), Java 21, Maven with the wrapper. Group com.amanahconnect, artifact amanah-connect-api.
- Dependencies: web, validation, security, data-jpa, postgresql, flyway-core + flyway-database-postgresql, actuator, mail, thymeleaf, springdoc-openapi, lombok, mapstruct (with annotation processor config), micrometer-registry-prometheus, spring-boot-starter-test, spring-security-test, testcontainers (postgresql, junit-jupiter), shedlock (spring + jdbc provider), bucket4j, AWS SDK v2 (s3, s3-presigner), OpenPDF, ZXing core+javase, a TOTP library, logstash-logback-encoder, archunit.
- Create the package layout exactly as in the blueprint section 3.2 (empty package-info.java files are fine).
- Profiles: local, test, prod. application.yml with sane defaults, application-prod.yml reading everything from environment variables.
- Database config (IMPORTANT, Neon):
  * Use env vars DB_URL (jdbc:postgresql://HOST/DB?sslmode=require), DB_USER, DB_PASSWORD. JDBC does NOT accept user:pass@ in the URL, and do not include channel_binding.
  * Hikari: maximumPoolSize 10, minimumIdle 2, connectionTimeout 10s, maxLifetime 25 min (Neon closes idle connections).
  * Flyway must use a DIRECT (non-pooled) connection: add FLYWAY_URL/FLYWAY_USER/FLYWAY_PASSWORD separately, because Flyway's advisory lock does not work through PgBouncer transaction pooling. The app itself uses the pooled endpoint (host contains "-pooler").
  * spring.jpa.hibernate.ddl-auto=validate, open-in-view=false, jdbc.time_zone=UTC.
- Local development: docker-compose.local.yml with Postgres 16 and Mailpit (SMTP catcher), and a profile "local" that uses them.
- Logging: JSON logs in prod via logstash encoder, human-readable in local. Add a request-id filter that puts X-Request-Id into MDC and the response header.
- Actuator: expose only health (with liveness and readiness groups) publicly; info and prometheus on a separate management port 8081 not exposed to the internet.
- A /api/v1/ping endpoint returning version and time, and a Flyway baseline migration V1__init_extensions.sql enabling citext and pgcrypto.
- A .github/workflows/backend-ci.yml that runs ./mvnw verify on pull requests.

FRONTEND (frontend/): only scaffold now (Vite react-ts, strict TS). Real setup is a later prompt.

Done when: ./mvnw verify passes; the app starts against docker-compose.local.yml; GET /api/v1/ping works; GET /actuator/health/readiness is UP; the same app boots against a Neon database when the three DB env vars are set (explain how to test it, do not hardcode anything).
```

### PROMPT B1 — Database schema (Flyway) and entities

```
Read CLAUDE.md and blueprint sections 3.3 to 3.5. Create the full schema as Flyway migrations (V2 onward, split logically: identity, community+plans, members, billing, ledger, complaints+support+announcements, notifications+audit+leads) and the JPA entities, repositories and enums.

Requirements:
- UUID primary keys (use gen_random_uuid()), created_at/updated_at timestamptz (UTC), @Version optimistic locking on invoices, payment_records, ledger_entries, communities.
- Use citext for emails. NUMERIC(14,2) for money. jsonb for settings/limits/custom fields.
- All foreign keys, CHECK constraints for enums/status/amounts (amount > 0 where applicable), UNIQUE (community_id, member_no), UNIQUE (community_id, invoice_no), UNIQUE (community_id, receipt_no).
- Indexes on every community_id, on (community_id, status), (community_id, due_date), (community_id, entry_date), email columns, and token_hash columns.
- audit_logs: add a trigger that RAISES EXCEPTION on UPDATE or DELETE (append-only).
- A document_counters table (community_id, counter_type, financial_year, last_value) used with SELECT ... FOR UPDATE to generate gap-free invoice and receipt numbers, plus a NumberingService with a test that proves no gaps or duplicates under 20 concurrent threads.
- Seed data migration: the three default plans (Starter, Growth, Enterprise) with limits and features in jsonb, and one generic list of default ledger categories (Maintenance, Utilities, Repairs, Events, Donations, Administration, Other) stored as a reference table, copied into each new community on creation.
- Do NOT seed any user. The SuperAdmin is bootstrapped by a command in the next prompt.
- JPA: entities use @Getter/@Setter (no @Data on entities), explicit equals/hashCode on id, LAZY associations, no entity leaks to the API.
- Add a Testcontainers-based integration test that boots Postgres, runs all migrations, and verifies Hibernate validation passes.

Done when: ./mvnw verify passes including the migration test and the numbering concurrency test. Show the list of tables created.
```

### PROMPT B2 — Authentication and security core

```
Read CLAUDE.md and blueprint section 4. Implement the complete auth module in package auth plus config/security.

Features:
1. Login: POST /api/v1/auth/login (email, password). Returns access JWT (15 min) in the body and sets the refresh token as HttpOnly, Secure, SameSite=Strict cookie scoped to /api/v1/auth. If the user has 2FA enabled, return a short-lived (5 min) "mfaToken" and require POST /auth/login/2fa with TOTP code or a recovery code.
2. Refresh: POST /auth/refresh rotates the refresh token (opaque 256-bit, stored as SHA-256 hash in refresh_tokens, family_id). Detect reuse of a rotated token and revoke the entire family. Require header X-Requested-With: amanah-web and validate Origin.
3. Logout (revokes current token family), logout-all.
4. Lockout: 5 consecutive failures lock the account for 15 minutes (failed_attempts, locked_until). Identical error for unknown email and wrong password. Constant-time-ish behaviour (hash a dummy password for unknown emails).
5. Password: BCrypt strength 12. Policy min 10 chars with a basic strength check. Forgot/reset flow with single-use hashed tokens (30 min expiry); the forgot endpoint always returns 202 regardless of whether the email exists. On password change/reset, revoke all refresh tokens.
6. Invitation acceptance: POST /auth/accept-invite (token, new password) used when SuperAdmin creates a Community Admin. Token valid 48 hours, single use. After accepting, the user must set up 2FA if the community or role requires it.
7. 2FA (TOTP): setup (returns otpauth URI + secret), enable (verify first code, return 10 recovery codes once), disable (requires password + code). Encrypt the TOTP secret with AES-GCM using a key from env TOTP_ENC_KEY. Mandatory 2FA for SUPER_ADMIN: if not enabled, the access token carries a "mfa_setup_required" claim and every endpoint except 2FA setup rejects it.
8. GET /auth/me returns user, role, community summary, flags.
9. Bootstrap: a CommandLineRunner that, ONLY if no SUPER_ADMIN exists AND env BOOTSTRAP_SUPERADMIN_EMAIL and BOOTSTRAP_SUPERADMIN_PASSWORD are set, creates the SuperAdmin with must_setup_2fa. It logs that it ran, never logs the password. Document that these env vars should be removed after first start.
10. Security config: stateless, CSRF disabled only for bearer-token endpoints with the cookie endpoints protected as above, strict CORS from env ALLOWED_ORIGIN, security headers (HSTS, nosniff, frame-ancestors none, referrer policy), method security enabled, deny by default, public matchers only for /public/**, /auth/**, health, docs (docs disabled in prod).
11. Rate limiting with Bucket4j: login 10/min per IP and 5/min per email, forgot-password 5/hour per IP, public lead form 5/hour per IP. Return 429 with Retry-After.
12. Audit every auth event (login success/fail, lockout, password change, 2FA changes, token reuse detected).
13. Global exception handling returning RFC 9457 problem+json with stable codes; never leak stack traces or whether an email exists.

Tests (required): login success/failure/lockout, refresh rotation and reuse detection, 2FA flow including recovery codes, password reset single-use, bootstrap idempotency, rate limit, and that a COMMUNITY_ADMIN token is rejected on /admin/** (403) and a SUPER_ADMIN token on /community/** where inappropriate.

Done when: ./mvnw verify passes and a short curl script (docs/auth-smoke.sh) demonstrates login, refresh, and 2FA setup against the running app.
```

### PROMPT B3 — Common layer: tenancy, audit, errors, pagination, plan limits

```
Read CLAUDE.md and blueprint section 3.3. Build the shared foundations in common, tenant, audit and plan packages.

1. TenantContext/TenantGuard: resolves the current community from the authenticated COMMUNITY_ADMIN principal (via community_users). Provide a @CurrentCommunity argument resolver so controllers never read community ids from input. A community with status SUSPENDED or ARCHIVED makes all /community/** write endpoints return 403 code COMMUNITY_SUSPENDED (reads allowed so admins can still export their data).
2. Hibernate @Filter on tenant entities enabled per request as a second safety net.
3. AuditService: record(action, entityType, entityId, before, after) capturing actor, community, IP, user agent, request id. Provide an @Audited annotation or aspect for controllers/services; make sure before/after never include secrets (password hashes, tokens, TOTP secrets) — add a redaction list and a test.
4. Pagination: standard PageRequest params (page, size max 100, sort whitelist) and a PageResponse<T> {items,total,page,size}.
5. Money: a Money value helper (BigDecimal scale 2, HALF_UP), a Jackson config serialising money as string, and validation annotations.
6. PlanLimitService: checkMemberLimit(communityId), checkStorage, checkEmailQuota. Throws PlanLimitExceededException -> 402 with code PLAN_LIMIT_EXCEEDED and a message naming the limit and the plan. Also feature flags: requireFeature(communityId, "pdf_reports").
7. Cross-tenant test harness: an abstract integration test base that creates two communities with admins and helpers to assert "admin A cannot read/update/delete B's resource -> 404". Every later module must extend it.
8. ArchUnit tests: controllers do not access repositories directly; entities are not returned from controllers; tenant repositories do not expose findById without communityId.

Done when: ./mvnw verify passes with the harness and ArchUnit rules in place.
```

### PROMPT B4 — SuperAdmin: communities, onboarding, plans, subscriptions, leads

```
Read CLAUDE.md. Implement the SUPER_ADMIN module under /api/v1/admin/** (all endpoints require SUPER_ADMIN with 2FA completed). Mutations are audited.

COMMUNITIES
- Create community (new): name, contact details, address, date of establishment, plan, owner name/email. Creates the community (status PENDING), the owner user (status INVITED) and sends a "set your password" invitation email via the outbox. Community becomes ACTIVE once the owner accepts, or SuperAdmin can activate it.
- List with search, filters (status, plan), sort, pagination, member counts, subscription expiry. Detail view. Update. Suspend/activate (with reason, audited, emails the owner). Archive (soft).
- Reset the community admin's password (forces a reset email, revokes sessions) and export community details (JSON and CSV: profile, owner, members count, subscription history).
- MIGRATION onboarding (Excel item "migration or new user"): POST /admin/communities/{id}/import accepting CSV for members and optionally opening balances and open invoices. Validate row by row, return a dry-run report (valid/invalid rows with reasons) before an explicit confirm step. Idempotent via an import batch id. Respect plan member limits.
- Read-only community overview for support (profile, counts, finance summary, recent activity). Every call writes an audit entry "SUPPORT_VIEW".

PLANS AND SUBSCRIPTIONS
- CRUD plans (code, name, prices, limits jsonb, features jsonb, public flag, active). Deactivating a plan never breaks communities already on it.
- Platform subscriptions: record a payment manually (community, plan, amount, reference, period start/end). List with status (ACTIVE, EXPIRING, EXPIRED). Endpoint for expiring in N days. A scheduled job (ShedLock) daily at 09:00 IST: email owners 7 and 1 days before expiry, mark expired, and optionally auto-suspend after a configurable grace period (default: warn only, never auto-suspend without a flag).

LEADS
- Public POST /api/v1/public/leads (name, email, phone, community name, size, message) with honeypot field, rate limiting, input length limits. Stores the lead, emails SuperAdmin, sends an acknowledgement to the lead. SuperAdmin endpoints to list, change status (NEW, CONTACTED, DEMO_SCHEDULED, CONVERTED, LOST) and "convert to community" which pre-fills community creation.

STATS
- GET /admin/stats: total/active/suspended communities, total members, platform revenue (this month, year), expiring subscriptions, new leads, open support threads. Use efficient aggregate queries.

Tests: each endpoint authorised correctly, plan limit on import, dry-run vs confirm, suspension behaviour, lead rate-limit and honeypot, subscription expiry job.

Done when: ./mvnw verify passes and OpenAPI shows all /admin endpoints grouped.
```

### PROMPT B5 — Community: settings, members, invites, self-registration

```
Read CLAUDE.md. Implement the COMMUNITY_ADMIN module for members. All queries are scoped by the tenant guard and every endpoint extends the cross-tenant test harness.

SETTINGS
- GET/PATCH /community/settings: profile (name, contact, address, logo upload via presigned S3 upload), currency, financial year start month, UPI ID and payee name (validated format), member group label ("Flat", "Family", "Batch"...).
- Notification settings: due-reminder days before due date, overdue reminder frequency, welcome email on/off, receipt email on/off.

MEMBERS
- CRUD with search (name, email, phone, member no), filters (status, group), sort, pagination. member_no auto-generated per community (prefix from slug) via the numbering service. Fields: full name, email, phone, group label, joined date, custom fields (jsonb, validated keys), consent_email.
- Activate/deactivate with reason. Soft delete only (and block deletion when unpaid invoices exist unless forced with a reason; audited).
- Enforce plan member limit on create, import and approving registrations.
- CSV import (dry-run then confirm) and CSV export (streamed).
- Welcome email on creation if the setting is on and the member has an email and consent.
- Dashboard counts: total, active, inactive, new this month.

INVITES AND SELF-REGISTRATION (no member login)
- Admin creates an invite link (token stored hashed, expiry, max uses, optional default group) and gets a QR code PNG for it. List, revoke.
- Public endpoints: GET /public/invites/{token} (community name, logo, form fields; returns a generic message if invalid/expired) and POST /public/invites/{token}/register (name, email, phone, group, consent checkbox, honeypot, rate limited). Creates a PENDING member_registration; emails the admin.
- Admin approves or rejects registrations (reject needs a reason); approval creates the member and sends the welcome email.
- Also: "invite by email" which sends the link to a given address.

Tests: limit enforcement, tenant isolation on every endpoint, import dry-run/confirm, invite expiry/max-use/revocation, duplicate email handling inside a community, public endpoints leak nothing about other communities.

Done when: ./mvnw verify passes.
```

### PROMPT B6 — Finance: fee plans, invoices, payments, receipts, UPI QR, ledger

```
Read CLAUDE.md and blueprint section 3.4. This is the most important module: be rigorous. Implement billing and ledger for COMMUNITY_ADMIN.

FEE PLANS AND INVOICES
- CRUD fee plans (name, kind MAINTENANCE/SUBSCRIPTION/DONATION/EVENT/FINE/OTHER, amount, frequency, due day, applies to ALL_ACTIVE / GROUP / SELECTED members).
- POST /community/invoices/generate for a fee plan and period: idempotent (a unique key of fee_plan + member + period prevents duplicates), creates ISSUED invoices with gap-free invoice numbers, queues the "bill" email per member. Also manual one-off invoice and donation record for a single member or anonymous donor.
- A scheduled job (ShedLock) generates recurring invoices on schedule and flips ISSUED/PARTIAL invoices to OVERDUE after the due date. Idempotent and safe to re-run.
- Invoice states: DRAFT, ISSUED, PARTIAL, PAID, OVERDUE, CANCELLED. Cancelling requires a reason and is audited. Totals are always recomputed server-side.

PAYMENTS (manual confirmation, no gateway)
- POST /community/invoices/{id}/payments (amount, method CASH/UPI/BANK/CHEQUE/OTHER, reference, received_on). Partial payments supported, overpayment rejected. Use optimistic locking and a DB transaction so two admins cannot double-record. Idempotency-Key header supported.
- Creating a payment: updates invoice amount_paid/status, creates a receipt with a gap-free number (format PREFIX-FY/000123), generates the PDF (OpenPDF: community logo, name, address, receipt no, member, amount in figures and words, method, date, signature line), stores it in S3, emails it to the member, and auto-creates a linked INCOME ledger entry.
- Reversal: POST /community/payments/{id}/reverse (reason required) creates an offsetting payment record and ledger entry; nothing is deleted. Receipts remain, marked "reversed".
- Resend receipt/bill email.
- Payment interface: define a PaymentGateway interface with a ManualGateway implementation so a real gateway can be added later without touching billing logic.

UPI QR / PAYMENT LINK (Excel: "QR generation or link for payment")
- GET /community/invoices/{id}/qr returns a PNG QR encoding a standard UPI deep link: upi://pay?pa=<upi_id>&pn=<payee>&am=<balance>&cu=INR&tn=<invoice no>. Requires the community's UPI ID to be set.
- A public, tokenised payment info page endpoint GET /public/pay/{token} showing community name, invoice number, amount due, the QR and "pay with UPI app" link. The token is unguessable, expires, and reveals no other member data. The bill email contains this link. Be explicit in the response that confirmation is manual (the admin marks it paid after checking their bank/UPI app).

LEDGER (BUDGET)
- Category CRUD (income/expense), seeded from the generic default category list on community creation.
- Manual ledger entries (income/expense) with category, date, amount, notes, optional attachment (receipt image/PDF via presigned S3 upload with type/size validation). Entries from payments are read-only (edit via reversal).
- GET /community/ledger/summary: total income, expense, net, by category, by month, with date-range filters; plus an "opening balance" setting.
- Reports (CSV and PDF): income vs expense statement, category breakdown, collection report (billed vs collected vs outstanding), member dues statement, defaulters list, receipts register. PDF reports behind the "pdf_reports" plan feature flag.

Tests (required): invoice generation idempotency, numbering without gaps under concurrency, partial then full payment, overpayment rejected, double-submit with the same Idempotency-Key, reversal arithmetic, ledger totals equal payments, UPI link format, tenant isolation on every endpoint, overdue job.

Done when: ./mvnw verify passes and docs/finance-flow.md describes the invoice->payment->receipt->ledger flow with an example.
```

### PROMPT B7 — Complaints, support chat, announcements

```
Read CLAUDE.md. Implement three modules.

COMPLAINTS (inside a community)
- CRUD: member (optional), subject, description, priority (LOW/MEDIUM/HIGH/URGENT), category, status (OPEN/IN_PROGRESS/RESOLVED/CLOSED), assigned admin. Comment thread with internal notes vs member-visible updates. Status changes timestamp resolved_at and optionally email the member a status update. Filters, search, counts for the dashboard, SLA indicator (age in days).

SUPPORT CHAT (Excel L1 "Community Help Desk (Chat)": Community Admin <-> SuperAdmin)
- Threads with subject, priority, status (OPEN/WAITING/RESOLVED/CLOSED). Messages with optional attachment (S3, validated). Unread counters for both sides. Community admin sees only their own threads; SuperAdmin sees all with filters and can assign/close. Email notification to the other side on new message (debounced so a burst sends one email).
- Real-time feel without complexity: clients poll every 10 seconds for new messages using an efficient "since" cursor endpoint. Design the API so it can be swapped for WebSocket/SSE later.

ANNOUNCEMENTS
- Community announcements: title, rich-text-safe body (sanitize HTML on the server with an allow-list), audience (ALL_ACTIVE / GROUP / SELECTED), send_email flag, draft/scheduled/sent states. Sending queues one outbox email per recipient with consent, respects the plan email quota, and records delivery counts. Provide a preview and a "send test to me" action.
- Platform announcements by SuperAdmin to all or selected communities' admins (emails and in-app banner), including offers. Community admins see them in a notification area.

Tests: tenant isolation, chat visibility rules, HTML sanitisation (XSS payloads stripped), quota enforcement, scheduled announcement dispatch.

Done when: ./mvnw verify passes.
```

### PROMPT B8 — Email engine, outbox, scheduled jobs, S3

```
Read CLAUDE.md. Build the notification and file infrastructure used by all modules.

EMAIL
- Transactional outbox: services insert into email_outbox inside the same DB transaction as the business change. A scheduled worker (ShedLock, every 30s) sends pending mails via SES SMTP with exponential backoff (max 6 attempts), marks FAILED with the error, and is safe to run on multiple instances. Never send email inside the request thread.
- Per-community daily/monthly quota accounting against plan limits. Suppression list: hard bounces and complaints (SES via SNS webhook endpoint, signature verified) mark addresses as suppressed and the app never emails them again.
- Thymeleaf HTML templates (responsive, inline CSS, brand colours deep teal #07363E / teal #037077 / gold accent #C9A227, community name and logo in the header, plain-text alternative): welcome, invitation, password reset, 2FA changed, member bill, payment reminder, overdue notice, receipt (with PDF attached or secure link), announcement, registration received/approved/rejected, complaint update, support message, subscription expiring/expired, lead acknowledgement, new-lead alert. Every member-facing email has a footer with community contact details and an unsubscribe/contact-admin note.
- A template preview endpoint for SuperAdmin (dev/staging only) and a golden-file test that renders every template with sample data.
- Scheduled reminder job: due-soon and overdue reminders per community notification settings, idempotent per invoice and day.

FILES
- S3 service: presigned PUT (content-type and size constrained) and presigned GET (short expiry), key naming communities/{communityId}/{type}/{uuid}, server-side validation of extension plus magic bytes after upload, storage usage counted against plan limits. Local profile uses MinIO or a filesystem stub behind the same interface.

Tests: outbox retry/backoff, idempotent reminders, template rendering, suppression, presign constraints, storage quota.

Done when: ./mvnw verify passes. In local, emails appear in Mailpit with correct branding.
```

### PROMPT B9 — Dashboards, exports, audit viewer

```
Read CLAUDE.md. Implement the remaining read-side features.

- GET /community/dashboard: members (total/active), collection this month (billed/collected/outstanding), overdue count and amount, net balance, open complaints, unread support messages, upcoming dues, recent activity (last 10 audit entries relevant to the community, humanised), monthly collection trend (12 months). Optimise queries (indexes, no N+1) and cache short-lived aggregates with Caffeine (30s).
- GET /admin/audit with filters (actor, community, action, entity, date range) and keyset pagination; GET /admin/audit/export (CSV streamed, limited to 100k rows, itself audited). A community admin can view their own community's audit trail (read-only) at /community/audit.
- Data export for a community (all tables as a ZIP of CSVs) generated asynchronously, stored in S3, and emailed as a time-limited link. Supports the community's data-portability needs.
- Member data deletion/anonymisation action (right to erasure) that anonymises personal fields but preserves financial records.

Tests: dashboard numbers match seeded data, export contents, audit filters, anonymisation keeps ledger integrity.

Done when: ./mvnw verify passes.
```

### PROMPT B10 — Hardening, observability, performance, docs

```
Read CLAUDE.md. Production-harden the backend.

1. Security review pass: run through the blueprint section 4 checklist and fix gaps; add OWASP dependency-check to CI; verify no endpoint is accidentally public (a test that enumerates all mappings and asserts expected auth rules); confirm secrets never appear in logs (test with a log-capture assertion on login/reset flows).
2. Observability: Micrometer metrics for outbox depth, failed emails, login failures, invoice generation, job durations; readiness check includes DB and (optionally) S3; structured logs with request id, user id (hashed) and community id; slow-query logging.
3. Performance: verify indexes with EXPLAIN on the main list/aggregate queries using a seeded dataset of 50 communities, 20k members, 200k invoices; add a Gatling or k6 smoke script for login, dashboard and invoice list; document results and targets (p95 under 300ms for list endpoints).
4. Resilience: graceful shutdown, timeouts on all outbound calls, Hikari leak detection in test, handling Neon cold-start/connection drops with retry on idle-connection errors at startup.
5. Documentation: OpenAPI with examples, docs/architecture.md, docs/runbook.md (deploy, rollback, restore DB, rotate secrets, common incidents), docs/security.md, docs/data-model.md (generated ER diagram in Mermaid).
6. Test coverage report in CI with a sensible floor (e.g. 75% on services), and a fast test profile.

Done when: ./mvnw verify passes in CI mode, the endpoint-auth test passes, and the docs exist.
```

---

## PHASE F — FRONTEND (React 19 + TypeScript)

Keep the folder structure you already made (feature-based). Additions: `src/shared/components/ui/` becomes the design system, `src/lib/` gets `money.ts`, `errors.ts`.

### PROMPT F0 — Foundation, tokens, tooling

```
Read CLAUDE.md. Set up the frontend foundation in frontend/ (Vite + React 19 + TypeScript strict). Keep the existing feature-based folder structure (features/landing, auth, superadmin, community-admin; shared/{components,hooks,utils}; lib; routes; store).

1. Tooling: ESLint 9 flat config with typescript-eslint (no-explicit-any as error), Prettier, Vitest + React Testing Library + jest-axe, Playwright, path alias @ -> src, husky + lint-staged, .env.example (VITE_API_URL).
2. Tailwind 3.4 with the Amanah design tokens in tailwind.config.js: colors brand-50..950 (950 #04262C, 900 #07363E, 800 #0A4A52, 600 #037077, 500 #038D8F, 100 #D6EFEF, 50 #EEF8F8), gold (500 #C9A227, 600 #8A6A12, 200 #F0E2A8, 50 #FBF6E3), ivory #FAFAF7, ink #0F2A2E, muted #5B6F72, success #3A8C10, premium #5B2C91, danger #B42318, warning #B54708. Fonts: Cinzel (wordmark/eyebrows), Playfair Display (headings), Inter (body). Self-host fonts (fontsource) instead of Google CDN for performance and privacy. Rounded-xl/2xl defaults, soft teal shadows, container widths.
3. src/lib/axios.ts: baseURL /api/v1, withCredentials true, header X-Requested-With: amanah-web, Bearer access token from the in-memory auth store, single-flight refresh on 401 (queue concurrent requests, retry once, on failure log out and redirect to /login). Parse RFC 9457 problem+json into a typed ApiError with code, status, detail and field errors.
4. src/store/authStore.ts (Zustand, memory only): user, accessToken, isInitializing, mfa state; actions. Bootstrap on app start by calling /auth/refresh then /auth/me.
5. src/lib/queryClient.ts with sensible defaults (staleTime 60s, no retry on 4xx), and typed query key factories per feature.
6. src/types: TypeScript types for all API DTOs (mirror the backend OpenAPI; add a script `npm run gen:api` that generates types from the backend /v3/api-docs using openapi-typescript).
7. src/lib/money.ts (string-based money, format with Intl en-IN, never floats for arithmetic: use a tiny decimal helper), dates (date-fns, IST display), and an errors.ts mapping API error codes to friendly messages.
8. routes/index.tsx with lazy-loaded route chunks and an error element; ProtectedRoute with role guard, spinner while initializing, "mfa setup required" redirect.
9. Global ErrorBoundary, Suspense fallbacks, Sonner toaster, 404 page.
10. Copy brand assets from the repo's brand/ folder to public/brand/. Add favicon, web app manifest, theme-color #07363E.
11. Nginx-friendly build: hashed assets, source maps hidden in prod.

Done when: npm run lint, npm run build and npm test pass; the app boots with placeholder routes for every page in the folder structure.
```

### PROMPT F1 — Design system (shared UI library)

```
Read CLAUDE.md. Build the shared design system in src/shared/components/ui using Radix primitives with Tailwind (shadcn/ui pattern, owned code, no heavy UI kit). Everything typed, accessible (focus rings in brand-500, keyboard support, ARIA), and documented with a dev-only /styleguide route showing every component and state.

Components: Button (primary teal, secondary outline, ghost, danger, gold-accent for premium CTAs; loading state), Input, Textarea, Select, Combobox (searchable), Checkbox, RadioGroup, Switch, DatePicker, MoneyInput (en-IN formatting, decimal-safe), FormField with React Hook Form + Zod integration and inline error text, Card, Badge/StatusBadge (paid, pending, overdue, active, suspended, etc. with colour AND icon/text, never colour alone), Tabs, Dialog, ConfirmDialog (danger variant requiring typed confirmation for destructive actions), Sheet/SlideOver, DropdownMenu, Tooltip, Toast helpers, Skeleton, EmptyState (with illustration slot and CTA), ErrorState with retry, Pagination, DataTable (TanStack Table: server-side pagination/sort/filter, column visibility, sticky header, row actions, responsive card fallback on mobile, CSV export hook), StatCard (with trend), Avatar, FileUpload (drag and drop, presigned-URL upload with progress, type/size validation), Stepper, CopyField, QrCard, PageHeader, Breadcrumbs, SearchInput (debounced).

Also: layout shells in src/shared/components/layout: AppShell with a collapsible deep-teal sidebar (logo, nav with active wave/gold indicator, user menu), top bar (community name, notifications bell, help), mobile bottom sheet navigation, and a PublicLayout for marketing/auth pages.

Write tests for the form components, DataTable behaviour and axe checks on every component.

Done when: /styleguide renders all components, npm test (including axe) and npm run build pass.
```

### PROMPT F2 — Landing page (public site)

```
Read CLAUDE.md. Build the public landing page at src/features/landing/ as a premium, trustworthy marketing site matching the Amanah Connect logo: deep teal (#07363E), teal (#037077), gold accents (#C9A227, gold-600 for gold text on light), ivory background, Cinzel eyebrows, Playfair Display headings, Inter body. Calm, dignified, warm. Not generic SaaS purple gradients.

Sections (all responsive, mobile-first, fast):
1. Sticky navbar: logo (public/brand/logo-lockup-transparent.png or the mark + Cinzel wordmark), links (Solutions, Features, Security, Pricing, FAQ), "Login" (outline) and "Request a demo" (primary). Mobile drawer.
2. Hero: eyebrow "CONNECTING COMMUNITIES WITH TRUST", H1 "Run your community with clarity, care and complete transparency." Subtext covering membership, dues and donations, budgets, complaints and announcements in one secure place. CTAs: "Request a demo", "See how it works". Right side: a coded product preview (a React-built dashboard mock with sample numbers, NOT a screenshot) floating on a teal gradient with the logo's wave as the section's bottom divider and a faint geometric lattice pattern at 5% opacity. Trust strip below with short, generic proof points ("Members never need to log in", "Receipts by email", "Complete audit trail", "Your data stays yours") with small icons.
3. "Everything your community runs on": use-case cards, not audience types: collect monthly dues, record donations, track spending against budget, handle member complaints, keep everyone informed. Each card has a one-line pitch and a small example. Keep the copy universal so it fits any community.
4. Feature bento grid (6 to 8 cards, lucide icons): Member management, Dues and donations, Instant receipts by email, UPI QR payments, Budget and financial reports, Complaint tracking, Announcements, Audit trail and 2FA.
5. "Why Amanah" values band using the logo's four values (Trust, Integrity, Collaboration, Excellence) with the shield, scales, handshake and star icons, gold hairlines, deep teal background.
6. How it works: 3 steps (We set up your community, add or invite members, collect and report with confidence) with the dashed connector.
7. Transparency and security: bullets on encryption, two-factor authentication, role-based access, audit logs, daily backups, data export. Be factual; no fake certifications or fake numbers.
8. Pricing: loads plans from GET /public/plans (fallback to static copy on error), monthly/yearly toggle, highlighted middle plan, Enterprise with "Contact us" using the premium purple accent.
9. FAQ accordion (Radix) with at least 8 honest questions (do members need to log in? how do payments work? is our data safe? can we migrate from Excel? etc.).
10. Demo request form (React Hook Form + Zod) posting to /public/leads: name, email, phone, community name, approximate members, message; honeypot field; success state; accessible errors.
11. Final CTA band and footer (logo, tagline, links, copyright, privacy and terms links to placeholder pages).

Quality bar: Lighthouse mobile Performance >= 90, Accessibility >= 95, SEO >= 95. Self-hosted fonts, lazy-loaded below-the-fold sections, images with width/height, AVIF/WebP where possible, prefers-reduced-motion respected, subtle framer-motion fade/slide only. Add react-helmet-async metadata, Open Graph/Twitter tags, JSON-LD (Organization, SoftwareApplication), sitemap.xml, robots.txt. Include Privacy Policy and Terms pages as clearly marked drafts needing legal review.

Do not invent customer logos, testimonials, statistics or awards. Use clearly labelled placeholders for testimonials that the client can fill later or omit the section.

Done when: build passes, axe tests pass, Lighthouse run output is shown, and the form works end-to-end against the backend.
```

### PROMPT F3 — Auth pages

```
Read CLAUDE.md. Build src/features/auth/: Login, TwoFactorChallenge (6-digit TOTP input with auto-advance and a recovery-code mode), ForgotPassword, ResetPassword, AcceptInvite (set password, password strength meter), TwoFactorSetup (QR code, manual secret, verify, show recovery codes once with copy/download and a confirmation checkbox), Unauthorized.

Use PublicLayout with a split screen: left deep-teal panel with logo mark, tagline and the four values; right the form card on ivory. Full keyboard and screen reader support, show/hide password, caps-lock hint, rate-limit (429) and lockout messages shown clearly with the retry time. Never reveal whether an email exists. After login redirect by role (SUPER_ADMIN -> /superadmin/dashboard, COMMUNITY_ADMIN -> /admin/dashboard) and handle "mfa setup required" by forcing the setup flow. Session-expired state shows a friendly message.

Write tests for the login/2FA state machine and form validation, plus a Playwright e2e for login -> dashboard redirect and logout.

Done when: tests, build and e2e pass.
```

### PROMPT F4 — SuperAdmin panel

```
Read CLAUDE.md. Build src/features/superadmin/ (pages: Dashboard, Communities (+ detail), Plans, Subscriptions, Invites, Leads, Support, Announcements, Audit). Use the design system and DataTable. API layer in api/, TanStack Query hooks in hooks/, query key factories, optimistic updates only where safe.

- Dashboard: stat cards (communities, active, suspended, members, revenue this month/year, expiring soon, new leads, open support), 12-month revenue and new-communities charts (Recharts, brand palette), expiring-subscriptions list, recent leads, quick actions.
- Communities: searchable/filterable table (status, plan), create community wizard (details -> plan -> owner -> review), detail page with tabs (Overview, Admin and contact, Plan and subscription, Activity, Support), actions: suspend/activate (reason dialog), reset admin password, export details, archive. Migration import wizard: upload CSV -> dry-run results table with row errors -> confirm.
- Read-only "support view" of a community's overview with a visible banner "You are viewing community data (this access is logged)".
- Plans: cards + editor (limits and features), preview how the plan appears on the landing page.
- Subscriptions: table with expiry badges, record-payment slide-over, expiring-in-N-days filter.
- Invites: pending/accepted/expired admin invitations, resend, revoke.
- Leads: pipeline (status filter or kanban), lead detail, "convert to community" pre-filling the wizard.
- Support: inbox layout (thread list + conversation, unread counts, attachments, polling every 10s with visibility-aware pausing), assign/close.
- Announcements: composer with audience selection, preview, schedule, history.
- Audit: filter bar (actor, community, action, date range), table with expandable before/after diff view, CSV export.

Every page has loading, empty and error states; destructive actions use ConfirmDialog; forms use Zod schemas matching backend validation; show field-level errors from problem+json. Add component tests for key flows and Playwright e2e for create community -> appears in list -> suspend.

Done when: lint, build, tests, e2e pass.
```

### PROMPT F5A — Community Admin: dashboard, members, invites

```
Read CLAUDE.md. Build part 1 of src/features/community-admin/.

- Layout: AppShell with community name/logo in the header, sidebar (Dashboard, Members, Billing, Budget, Complaints, Announcements, Support, Settings), notification bell (platform announcements, new registrations), subscription-status warning banner when the plan is expiring or the community is suspended (writes disabled with an explanation).
- Dashboard: stat cards (active members, collected this month, outstanding, overdue, net balance, open complaints), collection trend chart, overdue/defaulter mini-table, recent activity timeline, quick actions (Add member, Create invoice, Record payment, Add expense).
- Members: DataTable with search, group and status filters; add/edit slide-over; activate/deactivate with reason; member detail page (profile, dues statement, payment history, complaints, communication log); CSV import wizard (dry-run results then confirm); CSV export; plan-limit meter ("43 of 50 members") with an upgrade hint at 90%.
- Invites and registrations: create invite link dialog with copy, QR code (download PNG/print-ready card using community logo), expiry and max uses, revoke; Pending registrations queue with approve/reject (reason) and bulk approve; "invite by email" form.
- Public pages (in features/community-admin/public or features/public): the member self-registration page at /join/:token (community branding, consent checkbox, thank-you state, invalid/expired state) and the payment info page at /pay/:token (amount due, UPI QR, open-in-UPI-app button, clear note that the payment will be confirmed by the community admin).

Handle PLAN_LIMIT_EXCEEDED responses with a friendly dialog. Include tests and Playwright e2e: add member -> appears; create invite -> register on public page -> approve.

Done when: lint, build, tests, e2e pass.
```

### PROMPT F5B — Community Admin: billing and budget

```
Read CLAUDE.md. Build part 2 of src/features/community-admin/ (Billing and Budget). Money is always string-based with the money helper; never use JS floating point for arithmetic.

BILLING (route /admin/billing with tabs)
- Invoices: DataTable with status tabs (All, Issued, Partial, Paid, Overdue, Cancelled), member search, due-date filter, totals row. Row actions: view, record payment, QR, resend bill, cancel (reason).
- Fee plans: list/editor (kind, amount, frequency, due day, applies to). "Generate invoices" wizard: choose fee plan and period -> preview count and total -> confirm; shows idempotent results ("32 created, 5 already existed").
- Record payment slide-over: amount (prefilled with balance), method, reference, received date; prevents overpayment client-side and server-side; shows the generated receipt with download and "email again". Idempotency-Key sent automatically and reused on retry.
- Invoice detail: timeline (issued, reminders sent, payments, receipts, reversals), QR card (UPI), copyable payment link, printable invoice. Reverse payment dialog with required reason and clear explanation.
- Receipts register and Defaulters list with "send reminder to all overdue" (confirm dialog showing count).
- Clearly worded notice near QR: payments are confirmed manually by the admin.

BUDGET
- Summary header (income, expense, net, opening balance) with date-range selector (this month, quarter, financial year, custom).
- Charts: income vs expense by month, expense by category donut.
- Ledger table with filters (type, category, date), add income/expense slide-over with attachment upload, payment-linked entries shown read-only with a link to the payment, reversal entries visible.
- Category manager.
- Reports page: choose report (income/expense statement, category breakdown, collection, member dues statement, defaulters, receipts register), format CSV/PDF, date range; PDF gated by plan feature (show an upgrade prompt when not available). Large exports show progress and download.

Tests: money arithmetic helper, payment form validation, invoice generation wizard, Playwright e2e: generate invoices -> record partial payment -> receipt available -> ledger shows income -> reverse.

Done when: lint, build, tests, e2e pass.
```

### PROMPT F5C — Community Admin: complaints, announcements, support, settings

```
Read CLAUDE.md. Build part 3 of src/features/community-admin/.

- Complaints: table and kanban toggle (Open, In Progress, Resolved, Closed) with drag-to-change-status (also available by menu for accessibility), priority badges, age/SLA indicator, detail drawer with comment thread (member-visible vs internal note toggle), assign, status change with optional "notify member by email", create complaint on behalf of a member.
- Announcements: composer with sanitised rich text editor (Tiptap, limited toolbar), audience selector (all active, group, selected), email toggle with recipient count and email-quota meter, preview, "send test to me", schedule, history with delivery stats.
- Support (chat with platform SuperAdmin): thread list and conversation view, attachments, unread badges, polling every 10 seconds paused when the tab is hidden, new-thread dialog, status badges.
- Settings (tabs): Community profile (logo upload, contact, address, financial year), Payments (UPI ID and payee name with validation and a test QR preview), Notifications (reminder days, overdue frequency, welcome and receipt toggles with email previews), Security (change password, 2FA enable/disable with recovery codes, active sessions list with revoke), Plan and usage (current plan, limits meters, subscription dates, history, "contact us to upgrade"), Data (export all data, request member anonymisation).

Tests and Playwright e2e: create complaint -> comment -> resolve; send announcement; support message round trip with the SuperAdmin session.

Done when: lint, build, tests, e2e pass.
```

### PROMPT F6 — Polish, accessibility, performance, PWA

```
Read CLAUDE.md. Production-polish the frontend.

1. Accessibility: run axe on every route in Playwright, fix all serious and critical issues; verify focus management in dialogs/sheets, skip-to-content link, visible focus, colour contrast (gold text uses gold-600), reduced motion, 200% zoom reflow, screen reader labels on icon buttons and tables.
2. Performance: route-level code splitting, bundle analysis (rollup visualizer), keep the initial authenticated bundle under 250 KB gzip, lazy-load charts and the rich text editor, image optimisation, prefetch likely next routes, virtualise very long tables.
3. Resilience UX: offline banner, retry buttons, friendly 5xx/429 messages, session-expiry flow without losing form data (draft preserved in memory), network error toasts de-duplicated.
4. PWA: manifest, icons (192/512 maskable from the logo mark), service worker caching static assets only (never API responses), "Add to home screen" ready on mobile.
5. Responsive QA at 360, 768, 1024, 1440 widths; tables degrade to cards on mobile; touch targets >= 44px.
6. i18n readiness: wrap all strings with a lightweight i18n layer (react-i18next) with English as the only locale for now, logical CSS properties (start/end) so RTL can be enabled later without rewrites.
7. Add Sentry (or equivalent) behind an env flag, with PII scrubbing.
8. Final e2e smoke suite covering both roles.

Done when: Lighthouse and axe reports are attached for landing, login, SuperAdmin dashboard and Community dashboard, all green on the agreed thresholds, and CI runs lint, tests, build and e2e.
```

---

## PHASE D — DEPLOYMENT (EC2) AND OPERATIONS

### PROMPT D1 — Containers, Nginx, Compose

```
Read CLAUDE.md. Create the production deployment artifacts in deploy/.

1. backend/Dockerfile: multi-stage (Maven build -> Eclipse Temurin 21 JRE), non-root user, layered jar, JAVA_TOOL_OPTIONS with container-aware memory (e.g. -XX:MaxRAMPercentage=70), HEALTHCHECK on readiness, read-only root filesystem compatible with a tmpfs /tmp.
2. frontend/Dockerfile: multi-stage (node build -> nginx static). The final nginx image only serves the SPA with SPA fallback, immutable caching for hashed assets, no-cache for index.html.
3. deploy/nginx/ edge Nginx config as a separate container (or host service) that: terminates TLS, redirects HTTP to HTTPS, serves the SPA, proxies /api/ to the backend, sets security headers (HSTS with preload-ready settings, CSP tuned to the app including the S3 domain for images, X-Content-Type-Options, Referrer-Policy, Permissions-Policy, frame-ancestors none), gzip/brotli, request size limits (api 10m), rate limiting zones for /api/v1/auth and /api/v1/public, real client IP handling, and blocks access to /actuator.
4. deploy/docker-compose.prod.yml: services nginx (edge), web (spa), api; env from a root-only /opt/amanah/.env file; restart unless-stopped; resource limits; healthchecks with depends_on conditions; logging driver with rotation; internal network so only nginx publishes 80/443; actuator management port NOT published.
5. Certbot setup (webroot or the nginx plugin) with automatic renewal and a renewal test command; document the first-issue procedure.
6. deploy/.env.prod.example listing every variable with descriptions (DB_URL pooled, FLYWAY_URL direct, DB_USER/PASSWORD, JWT_SECRET, TOTP_ENC_KEY, ALLOWED_ORIGIN, SES SMTP settings, S3 bucket/region, APP_BASE_URL, BOOTSTRAP_SUPERADMIN_* (remove after first run), SENTRY_DSN). Explain how secrets are loaded from AWS SSM Parameter Store at boot by a small script into /opt/amanah/.env with 600 permissions.
7. Smoke test script deploy/smoke.sh (health, login page, API ping, security headers check).

Done when: `docker compose -f deploy/docker-compose.prod.yml config` validates, images build locally, and the stack runs locally against local Postgres with a self-signed cert.
```

### PROMPT D2 — CI/CD with GitHub Actions

```
Read CLAUDE.md. Create GitHub Actions workflows:

1. ci.yml on pull requests: backend verify (Testcontainers), frontend lint+test+build, Playwright e2e against a composed stack, dependency scans (OWASP dependency-check, npm audit, Trivy image scan), fail on high severity findings.
2. deploy.yml on tag v* or manual dispatch with an environment "production" requiring manual approval: build and push images to GitHub Container Registry (or ECR) tagged with the git SHA, then deploy to EC2 over AWS SSM Run Command (preferred, no open SSH) or SSH as fallback: pull images, run `docker compose up -d`, wait for readiness, run deploy/smoke.sh, and automatically roll back to the previous image tag if the smoke test fails. Flyway migrations run on api startup using the direct connection, and migrations must be backward compatible with the previous release (document the expand/contract rule).
3. A staging environment variant using a Neon branch database.
4. Dependabot config for maven, npm, docker, github-actions.
5. Use GitHub OIDC to assume an AWS role (no long-lived AWS keys in GitHub secrets).

Done when: workflows lint (actionlint), and a dry-run deploy to a throwaway host is documented step by step.
```

### PROMPT D3 — Backups, monitoring, runbook

```
Read CLAUDE.md. Produce operational tooling and docs in deploy/ops/ and docs/.

1. Backups: Neon point-in-time recovery is the primary; add a nightly pg_dump (custom format) from a cron/systemd timer on the EC2 to an S3 bucket with versioning, SSE-KMS, lifecycle (30 daily, 12 monthly), plus a restore script and a documented quarterly restore drill. Also S3 versioning on the uploads bucket.
2. Monitoring: CloudWatch agent config for logs and host metrics (CPU, memory, disk), alarms (instance status check, CPU > 80% 10m, disk > 80%, 5xx rate, readiness failing), SNS email alerts, and an external uptime check (e.g. UptimeRobot/Better Stack) hitting /api/v1/ping and the landing page. Dashboards for outbox depth and email failures from Prometheus metrics.
3. Server hardening script for Ubuntu 24.04: unattended-upgrades, UFW (80/443 only), SSH key-only and restricted to the admin IP (or SSM Session Manager only), fail2ban, swap file, time sync, Docker log rotation, non-root deploy user.
4. docs/runbook.md: deploy, rollback, restart, rotate each secret (JWT, TOTP key with re-encryption procedure, DB password), restore DB from backup, handle SES bounce spikes, handle a locked-out SuperAdmin (a break-glass procedure that is audited), incident severity levels and a post-mortem template.
5. docs/go-live-checklist.md from the checklist in the blueprint section 10.

Done when: scripts pass shellcheck and the runbook has copy-pasteable commands.
```

---

## 8. AWS SETUP RUNBOOK (you do this by hand once; Claude Code cannot click the console)

1. **Account safety:** root account MFA, create an admin IAM user/Identity Center user, billing alarm.
2. **Region:** `us-east-1` (same as Neon).
3. **EC2:** Ubuntu 24.04 LTS, `t3.medium` (2 vCPU, 4 GB) to start (`t3.small` is tight with Java + Nginx + Docker), 30 GB gp3 encrypted, **Elastic IP**, IMDSv2 required.
4. **Security group:** inbound 80 and 443 from anywhere; SSH **closed** (use SSM Session Manager) or restricted to your IP. No other ports.
5. **IAM instance role:** least privilege for the uploads bucket, the backups bucket, SSM Parameter Store read on `/amanah/prod/*`, CloudWatch agent, and SSM core. No access keys on the box.
6. **S3:** two private buckets (uploads, backups), block public access, versioning, default encryption, CORS on uploads for presigned PUT from your domain only.
7. **SES:** verify your domain, add DKIM, SPF and DMARC DNS records, **request production access** (new accounts start in sandbox and can only email verified addresses), configure bounce/complaint notifications to SNS and point the webhook to the app.
8. **DNS:** point your domain to the Elastic IP, then issue the TLS certificate with certbot.
9. **Neon:** upgrade to a paid plan for production (free tier limits and scale-to-zero cold starts are not suitable for live users), create a `production` branch and a `staging` branch, use the **pooled** connection string for the app and the **direct** one for Flyway, create a dedicated database role for the app (not the owner role), enable IP allow-list if your plan supports it (allow the Elastic IP).
10. **Secrets:** put them in SSM Parameter Store as SecureString, never in git or the Compose file.

---

## 9. HONEST LIMITS OF THIS SETUP (tell the client)

- **One EC2 = one point of failure.** If the instance dies, the site is down until it is restored. Mitigations: AMI snapshot, scripted rebuild (the runbook), monitoring alarms. The upgrade path later is two instances behind an Application Load Balancer with an Auto Scaling group. The app is stateless, so this works without redesign (move rate limiting to Redis at that point).
- **Deploys cause a few seconds of downtime** with single-instance Compose. Schedule off-peak or accept it.
- **Neon is a managed serverless database.** Fine for this workload on a paid plan, but you depend on a third-party service. Backups to S3 give you an exit.
- **Email deliverability** depends on a correctly configured sending domain and a clean reputation. Budget a day for SES setup and warm-up.
- **Not legal or compliance advice.** DPDP Act obligations, GST on the platform fee, and terms of service need review by a professional.

---

## 10. GO-LIVE CHECKLIST

**Security**
- [ ] Neon password rotated, app uses a non-owner DB role
- [ ] All secrets in SSM, none in git (run a secret scan on the repo history)
- [ ] SuperAdmin bootstrapped, 2FA enabled, bootstrap env vars removed
- [ ] Cross-tenant tests green; endpoint-auth enumeration test green
- [ ] HTTPS only, HSTS on, `/actuator` unreachable from the internet
- [ ] Dependency and image scans clean of high severity issues

**Reliability**
- [ ] Backups running and one restore drill completed
- [ ] Alarms firing to a real email/phone (send a test)
- [ ] Rollback tested once
- [ ] Load test numbers recorded

**Email**
- [ ] SES out of sandbox, SPF/DKIM/DMARC passing (check with mail-tester)
- [ ] Every template reviewed on Gmail and Outlook, mobile and desktop

**Product and legal**
- [ ] Privacy Policy and Terms reviewed by the client's lawyer
- [ ] Client has completed UAT on both roles with real-looking data
- [ ] Plans and prices loaded; subscription process rehearsed
- [ ] Support contact and escalation path agreed

**Pilot first:** launch with 1 to 3 friendly communities, watch logs and the outbox for two weeks, then open up.

---

## 11. WHAT TO DO RIGHT NOW

1. Rotate the Neon password. Create the `staging` and `production` branches.
2. Ask the client the questions in section 0.4 (especially: SVG logo, domain, languages, UPI usage).
3. Create the repo, add `CLAUDE.md` and `brand/`.
4. Run **B0**, then **B1**, and commit after each.

*Project AKHAMA — Amanah Connect V2*