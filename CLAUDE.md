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