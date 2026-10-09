# Security

The checklist is section 4 of the blueprint. This page says where each item is implemented and which test would fail if it broke.
Report a vulnerability to the platform owner privately; do not open a public issue.

## Authentication and sessions

| Requirement | Implementation | Test |
|---|---|---|
| BCrypt cost 12, password policy (≥ 10 chars, strength, not the user's name) | `AuthConfig`, `PasswordPolicy`; strength is 4 only in the test profile | `PasswordPolicyTest`, `PasswordFlowIT` |
| Access JWT 15 min; HS256 secret ≥ 32 bytes from the environment | `JwtService`, `AuthProperties.accessTtl` | `JwtAndRateLimitUnitTest`, `LoginIT` |
| Refresh token: 256-bit random, only its SHA-256 stored, 7-day sliding, **rotated on every use, reuse revokes the family** | `RefreshTokenService` | `RefreshTokenIT` |
| Refresh cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`; custom header + `Origin` check | `CookieEndpointGuardFilter` | `RefreshTokenIT`, `LoginIT` |
| Lockout: 5 failures → 15 min; generic errors; unknown emails lock out the same way (no enumeration, similar timing) | `LockoutService`, `GhostLockout`, dummy BCrypt check | `LoginIT` |
| Rate limits: login 10/min per IP and 5/min per email, password reset 5/h, public forms | `RateLimitService` (Bucket4j) | `RateLimitIT`, `RateLimitEmailIT`, `*RateLimitIT` for the public forms |
| 2FA mandatory for SUPER_ADMIN, optional for community admins; TOTP secret AES-256-GCM at rest; 10 hashed single-use recovery codes | `TwoFactorService`, `TotpSecretCipher`, `RecoveryCodes`, `MfaSetupEnforcementFilter` | `TwoFactorIT`, `TotpSecretCipherTest` |
| Reset / invite / verification tokens: single use, hashed in DB, short expiry | `AuthToken`, `Tokens` | `PasswordFlowIT` |

## Authorization and tenancy

* **Deny by default.** `SecurityConfig` lists the public endpoints; everything else needs a valid token. `/api/v1/admin/**` needs SUPER_ADMIN (with 2FA
  set up), `/api/v1/community/**` needs COMMUNITY_ADMIN.
* **`EndpointAuthIT` enumerates every controller mapping** and checks: only the listed public endpoints answer without a token (everything else 401);
  admin endpoints refuse community admins and community endpoints refuse platform staff (403); forged/expired tokens are refused. Adding a public
  endpoint means editing the allow-list in that test, which a reviewer will see.
* **Tenant comes from the authenticated principal only** (`TenantContextFilter`); no endpoint reads a community id from the path, query, header or body.
  Repositories for tenant data always take `communityId`; Hibernate's tenant filter is a second net. Cross-tenant access is **404**.
  `TenantCoverageIT` fails the build if a `/community` endpoint has no cross-tenant test. Architecture tests enforce the structure.
* Composite foreign keys in the database make a cross-community reference impossible even through a bug.
* Suspended/archived communities are read-only (writes → 403 `COMMUNITY_SUSPENDED`), evaluated on every request.

## Transport, headers, CORS

TLS 1.2+ at Nginx. API responses carry HSTS (1 year, subdomains), `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`,
`X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, and Spring Security's default `Cache-Control: no-cache, no-store` on API responses.
CORS allows only the configured app origin(s) (`ALLOWED_ORIGIN`), with credentials. Tested in `BackendSkeletonIT`.

## Data handling

* Parameterised queries only (JPA and `NamedParameterJdbcTemplate`); sort columns come from whitelists.
* Request limits: JSON bodies ≤ 1 MB (413 `PAYLOAD_TOO_LARGE`, also for chunked bodies), uploads ≤ 10 MB (multipart), CSV imports ≤ 5000 rows / 2 MB, page size ≤ 100. Tested in `ObservabilityIT`.
* Uploads are validated by declared size, extension **and** magic bytes, stored in a private bucket under a server-chosen key, and served through short presigned URLs (`FileService`).
* CSV exports neutralise formula cells (`=`, `+`, `-`, `@`) so a spreadsheet does not run member-typed text.
* HTML in announcements is sanitised (OWASP Java HTML Sanitizer, allow-list).
* Error responses never include stack traces or internal messages (`server.error.*`, `GlobalExceptionHandler`); a 500 carries only a request id.
* Right to erasure: `POST /community/members/{id}/anonymise` (see `docs/read-side.md`). Data export for portability: `/community/data-exports`.

## Logs and secrets

* **No secrets or personal data in logs.** `LogHygieneIT` captures all log output at DEBUG while exercising login, refresh (including token reuse),
  forgot/reset password and member create/update/error paths, and asserts that no password, access/refresh/reset token, JWT, email address, phone or
  member name appears. `LoginRequest.toString()` is redacted; `Masking` exists for the rare case an identifier must be logged.
* Log lines carry `requestId`, a short SHA-256 of the user id (`userHash`) and `communityId` (an opaque UUID), nothing else about the person.
* Secrets come from environment variables / SSM only (`JWT_SECRET`, `TOTP_ENC_KEY`, DB, SMTP, S3). Production refuses to start without them. `.env` is git-ignored.
* Audit rows hold ids, numbers and counts, never names or emails; reasons typed by users are the one free-text field (so they are permanent: do not put personal details in them).

## Audit

Every mutating endpoint writes an audit row in the same transaction (`@Audited` / `@AuditHandledBy`, enforced by an architecture test). Logins, failures,
lockouts, password and 2FA changes, exports, downloads of the audit trail itself and platform-staff access to community data are audited.
`audit_logs` is append-only through database triggers. Platform staff search it at `/admin/audit`; each community reads its own at `/community/audit`.

## Dependency hygiene

* Dependabot (Maven, npm, GitHub Actions) opens weekly update PRs.
* OWASP dependency-check runs in CI (`-Psecurity`, nightly and on every backend PR) and fails the build for CVSS ≥ 7 in runtime dependencies.
  Suppressions live in `backend/dependency-check-suppressions.xml` and need a reason and an expiry. Set the `NVD_API_KEY` repository secret.
* `npm audit` for the frontend belongs to the frontend pipeline.

## Known limits / residual risk

* Rate limits are in memory per instance (Bucket4j); with several instances the effective limit is per instance. Lockout is in the database and is global.
* Breached-password (HIBP) checking is not implemented (optional in the blueprint).
* Antivirus scanning of uploads is not implemented (optional in the blueprint).
* The access token is valid until it expires (15 min) even after "log out everywhere"; refresh tokens are revoked immediately.
* A stolen SUPER_ADMIN session is the worst case: that is why 2FA is mandatory and `/admin` access is audited.
