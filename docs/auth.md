# Authentication design notes

Implements blueprint section 4 and prompt B2. Code: `backend/src/main/java/com/amanahconnect/auth`
and `config/SecurityConfig`.

## Endpoints (`/api/v1/auth`)

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /login` | none | Returns the access token, or an MFA challenge when 2FA is on. Sets the refresh cookie. |
| `POST /login/2fa` | mfaToken | TOTP code or recovery code. |
| `POST /refresh` | cookie | Rotates the refresh token. Needs `X-Requested-With: amanah-web` and an allowed `Origin`. |
| `POST /logout` | cookie | Revokes the token family. Same header/Origin rules. Always 204. |
| `POST /logout-all` | bearer | Revokes every session of the user. |
| `GET /me` | bearer | User, community summary, flags. |
| `POST /password/forgot` | none | Always 202, whether or not the address exists. |
| `POST /password/reset` | link token | Single use, 30 minutes. |
| `POST /password/change` | bearer | Needs the current password; signs out everywhere. |
| `POST /accept-invite` | link token | Single use, 48 hours. Sets the password, activates the account. |
| `POST /2fa/setup`, `/2fa/enable`, `/2fa/disable` | bearer | Enable returns 10 recovery codes once. |

## Tokens

* **Access token**: HS256 JWT, 15 minutes, claims `sub`, `role`, `typ=access`, `mfa_setup_required`, `jti`.
* **MFA token**: 5 minutes, `typ=mfa`. The two types are not interchangeable.
* **Refresh token**: 256 random bits, stored as SHA-256 only, 7 days sliding, rotated on every use. Each
  login starts a *family*; presenting an already-used token revokes the whole family and audits
  `TOKEN_REUSE_DETECTED`. It lives in the cookie `amanah_refresh`: HttpOnly, Secure, SameSite=Strict,
  `Path=/api/v1/auth`. It is never in a response body.
* **Reset and invitation tokens**: 256 random bits, only the hash is stored, consumed with an atomic
  `UPDATE ... WHERE used_at IS NULL` so concurrent use cannot succeed twice.

## Decisions worth knowing

* **No account enumeration.** Unknown email and wrong password give the same response; a dummy BCrypt
  check keeps timing alike; unknown addresses lock out after the same five attempts as real accounts
  (in-memory, hashed, bounded); forgot-password is always 202.
* **Lockout** is counted under a row lock on the user, so parallel guesses cannot slip through. Wrong
  2FA codes and wrong current-password attempts count too.
* **TOTP replay protection**: the last accepted 30-second step is stored; a code for the same or an
  earlier step is refused.
* **TOTP secrets** are AES-256-GCM encrypted with the user id bound in as additional data.
* **Path matching** in security filters uses the decoded path (`RequestPaths`), never `getRequestURI()`;
  an ArchUnit rule enforces it.
* **mfa_setup_required**: SUPER_ADMIN always, plus any account with `must_setup_2fa` or whose community
  has `settings.require_2fa = true`. Such a token may only call 2FA setup/enable, `/auth/me` and
  `/auth/logout-all`. After enabling, call `/auth/refresh` to get a token without the flag.
* **Local keys**: in the `local` and `test` profiles missing `JWT_SECRET` / `TOTP_ENC_KEY` are replaced
  by random keys with a warning. Every other profile refuses to start without them.

## Known limits (deliberate or deferred)

* A valid access token keeps working for up to 15 minutes after the user is disabled or signs out.
  Refresh tokens are revoked immediately.
* Rate limits and the unknown-email lockout are in memory, per instance. Running several API
  instances needs a shared store.
* Two browser tabs refreshing the same token at the same moment will trip reuse detection and sign the
  user out. Strict by design; a short grace window can be added if it proves annoying.
* Forgot-password does a little more database work for a real address than for an unknown one. The
  difference is far below network jitter, but it is not zero.
* Reset and invitation emails are queued in `email_outbox` with the link (and so the raw token) in the
  payload. The email sender (blueprint B8) must clear the payload once an email is sent.
* Changing `TOTP_ENC_KEY` makes every enrolled 2FA secret unreadable.
* The Boot management port (8081) has no authentication of its own: it must never be published.
