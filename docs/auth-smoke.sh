#!/usr/bin/env bash
# Smoke test for the auth API against a RUNNING backend: login, refresh (rotation + reuse
# detection), 2FA setup/enable/login, logout.
#
#   ADMIN_EMAIL=you@example.com ADMIN_PASSWORD='...' ./docs/auth-smoke.sh
#
# The account must be ACTIVE and must not have 2FA enabled yet. The easiest way to get one on a fresh
# database is to start the backend once with BOOTSTRAP_SUPERADMIN_EMAIL / BOOTSTRAP_SUPERADMIN_PASSWORD
# set (then remove those variables). WARNING: this ENABLES 2FA on that account. The authenticator
# secret and recovery codes are printed at the end so you can keep using the account.
#
# Needs: bash, curl, python3 (standard library only, used for JSON and for the TOTP code).
# Optional: BASE_URL (default http://localhost:8080), ORIGIN (default http://localhost:5173, must be an
# allowed origin: ALLOWED_ORIGIN).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ORIGIN="${ORIGIN:-http://localhost:5173}"
: "${ADMIN_EMAIL:?set ADMIN_EMAIL}"
: "${ADMIN_PASSWORD:?set ADMIN_PASSWORD}"
API="$BASE_URL/api/v1"

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32mok\033[0m   %s\n' "$*"; }
fail() { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

# json <field.path>  : read a field from JSON on stdin ('' if missing)
json() { python3 -c 'import sys,json
d=json.load(sys.stdin)
for k in sys.argv[1].split("."):
    d = d.get(k) if isinstance(d, dict) else None
print("" if d is None else str(d).lower() if isinstance(d, bool) else d)' "$1"; }

# totp <base32 secret> [offset_seconds] : the code an authenticator app shows
totp() { python3 - "$1" "${2:-0}" <<'PY'
import sys, hmac, hashlib, struct, time, base64
secret = base64.b32decode(sys.argv[1] + "=" * (-len(sys.argv[1]) % 8))
counter = (int(time.time()) + int(sys.argv[2])) // 30
digest = hmac.new(secret, struct.pack(">Q", counter), hashlib.sha1).digest()
o = digest[-1] & 15
print("%06d" % ((struct.unpack(">I", digest[o:o+4])[0] & 0x7fffffff) % 1000000))
PY
}

# request <method> <path> [curl args...] : sets STATUS, HEADERS, BODY
request() {
  local method="$1" path="$2"; shift 2
  local out; out="$(mktemp)"
  STATUS="$(curl -sS -X "$method" -D "$out.h" -o "$out" -w '%{http_code}' "$API$path" "$@")"
  HEADERS="$(cat "$out.h")"; BODY="$(cat "$out")"; rm -f "$out" "$out.h"
}
cookie() { printf '%s' "$HEADERS" | tr -d '\r' | sed -n "s/^[Ss]et-[Cc]ookie: amanah_refresh=\([^;]*\);.*/\1/p" | head -1; }
expect() { [ "$STATUS" = "$1" ] || fail "$2: expected HTTP $1, got $STATUS: $BODY"; ok "$2 (HTTP $STATUS)"; }
JSON=(-H 'Content-Type: application/json')
WEB=(-H 'X-Requested-With: amanah-web' -H "Origin: $ORIGIN")

step "0. ping"
request GET /ping; expect 200 "GET /ping -> $BODY"

step "1. login with email + password"
request POST /auth/login "${JSON[@]}" -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
expect 200 "login"
[ "$(printf '%s' "$BODY" | json mfaRequired)" != "true" ] || fail "this account already has 2FA enabled; use a fresh account"
ACCESS="$(printf '%s' "$BODY" | json accessToken)"; REFRESH1="$(cookie)"
[ -n "$ACCESS" ] && [ -n "$REFRESH1" ] || fail "no access token / refresh cookie"
printf '%s' "$HEADERS" | tr -d '\r' | grep -i '^set-cookie: amanah_refresh' | grep -qi 'HttpOnly' || fail "cookie is not HttpOnly"
ok "access token in body (expiresIn=$(printf '%s' "$BODY" | json expiresIn)s), refresh token only in an HttpOnly cookie"
ok "mfaSetupRequired=$(printf '%s' "$BODY" | json mfaSetupRequired)"

step "2. GET /auth/me with the bearer token"
request GET /auth/me -H "Authorization: Bearer $ACCESS"; expect 200 "me"
ok "$(printf '%s' "$BODY" | json user.email) role=$(printf '%s' "$BODY" | json user.role)"

step "3. refresh rotates the token"
request POST /auth/refresh "${WEB[@]}" -H "Cookie: amanah_refresh=$REFRESH1"; expect 200 "refresh"
REFRESH2="$(cookie)"; [ -n "$REFRESH2" ] && [ "$REFRESH2" != "$REFRESH1" ] || fail "token was not rotated"
ok "new refresh token issued (different from the first)"

step "4. refresh without the CSRF header / Origin is refused"
request POST /auth/refresh -H "Cookie: amanah_refresh=$REFRESH2"; expect 403 "missing X-Requested-With"
request POST /auth/refresh -H 'X-Requested-With: amanah-web' -H 'Origin: https://evil.example' -H "Cookie: amanah_refresh=$REFRESH2"; expect 403 "foreign Origin"

step "5. replaying the ALREADY USED token is detected and revokes the whole family"
request POST /auth/refresh "${WEB[@]}" -H "Cookie: amanah_refresh=$REFRESH1"; expect 401 "reuse of token #1 refused"
request POST /auth/refresh "${WEB[@]}" -H "Cookie: amanah_refresh=$REFRESH2"; expect 401 "token #2 is now revoked too"

step "6. log in again and set up 2FA"
request POST /auth/login "${JSON[@]}" -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"; expect 200 "login"
ACCESS="$(printf '%s' "$BODY" | json accessToken)"; REFRESH="$(cookie)"
request POST /auth/2fa/setup -H "Authorization: Bearer $ACCESS"; expect 200 "2fa setup"
SECRET="$(printf '%s' "$BODY" | json secret)"
ok "otpauth URI: $(printf '%s' "$BODY" | json otpauthUri)"
request POST /auth/2fa/enable "${JSON[@]}" -H "Authorization: Bearer $ACCESS" -d "{\"code\":\"$(totp "$SECRET")\"}"; expect 200 "2fa enable"
RECOVERY="$(printf '%s' "$BODY" | python3 -c 'import sys,json; print(" ".join(json.load(sys.stdin)["recoveryCodes"]))')"
ok "10 recovery codes issued (shown once): $(printf '%s' "$RECOVERY" | wc -w | tr -d ' ')"

step "7. refresh to drop the mfa_setup_required restriction"
request POST /auth/refresh "${WEB[@]}" -H "Cookie: amanah_refresh=$REFRESH"; expect 200 "refresh"
ok "mfaSetupRequired=$(printf '%s' "$BODY" | json mfaSetupRequired)"

step "8. login now needs the second factor"
request POST /auth/login "${JSON[@]}" -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"; expect 200 "password step"
[ "$(printf '%s' "$BODY" | json mfaRequired)" = "true" ] || fail "expected an MFA challenge"
[ -z "$(cookie)" ] || fail "a session cookie was issued before the second factor"
MFA="$(printf '%s' "$BODY" | json mfaToken)"; ok "challenge issued, no session cookie yet"
# the code used to enable 2FA cannot be reused, so take the next 30-second step (accepted: +-1 step)
request POST /auth/login/2fa "${JSON[@]}" -d "{\"mfaToken\":\"$MFA\",\"code\":\"$(totp "$SECRET" 30)\"}"; expect 200 "second factor"
ACCESS="$(printf '%s' "$BODY" | json accessToken)"; REFRESH="$(cookie)"

step "9. logout revokes the session"
request POST /auth/logout "${WEB[@]}" -H "Cookie: amanah_refresh=$REFRESH"; expect 204 "logout"
request POST /auth/refresh "${WEB[@]}" -H "Cookie: amanah_refresh=$REFRESH"; expect 401 "refresh after logout refused"

printf '\n\033[32mAll auth smoke checks passed.\033[0m\n'
printf 'Authenticator secret : %s\nRecovery codes       : %s\n' "$SECRET" "$RECOVERY"
