# Development test accounts

One account for each kind of sign-in, so login can be tested for real. Code: `backend/src/main/java/com/amanahconnect/devseed/DevSeedData.java`.

## Turn it on

It only runs when **both** the `local` profile and `DEV_SEED_ENABLED=true` are set. It refuses to start without `DEV_SEED_PASSWORD`, and the password must pass the normal password policy.

```
DEV_SEED_ENABLED=true
DEV_SEED_PASSWORD=<choose one, 10+ characters>
```

Start the backend with those two environment variables (IntelliJ run configuration). Every start re-applies the accounts, so they are put back to the states below. All accounts share that one password.

**Warning:** the `local` profile points at the remote Neon database. These accounts are real rows there. Never enable this against production data. To switch them off:

```sql
update users set status = 'DISABLED' where email like '%@dev-seed.example.test';
```

## The accounts (all `@dev-seed.example.test`)

| Email prefix | Kind | What sign-in does |
|---|---|---|
| `owner` | Community owner, active community | Straight in to `/admin` |
| `admin.2fa` | Community admin with 2FA on | Code step; recovery code `DEVSEED-RECOV1` works once |
| `superadmin.2fa` | Super admin with 2FA on | Code step, then `/superadmin` |
| `superadmin.setup` | Super admin, 2FA not set up | Signed in but held at `/2fa/setup` |
| `owner.must2fa` | Owner of a community that requires 2FA | Held at `/2fa/setup` |
| `owner.suspended` | Owner of a suspended community | Signs in; changes are refused (`COMMUNITY_SUSPENDED`) |
| `locked` | Too many failed attempts | 423 `ACCOUNT_LOCKED` |
| `disabled` | Disabled | 401 `INVALID_CREDENTIALS`, same as a stranger |
| `invited` | Invited, never accepted | 401 `INVALID_CREDENTIALS`, same as a stranger |

Authenticator secret for the 2FA accounts (public, dev only): `DEVSEEDDEVSEEDDEVSEEDDEVSEED2222`. Add it to any authenticator app, or let `e2e/support/totp.ts` compute codes.

## Run the live tests

```
# backend with the seed on, frontend dev server on :5173 proxying to it (VITE_API_URL)
E2E_LIVE=1 E2E_DEV_SEED_PASSWORD=<password> E2E_BASE_URL=http://localhost:5173 \
  npx playwright test e2e/live-accounts.spec.ts --workers=1
```

Logins are paced (the backend allows 10 a minute per address), so the run takes about 2 minutes. The recovery-code test spends the single-use code; restart the backend to get it back.
