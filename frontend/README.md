# Amanah Connect: frontend

React 19, Vite, TypeScript (strict), Tailwind 3.4, TanStack Query, Zustand (auth only), React Router.
Rules for this codebase are in the repository's [CLAUDE.md](../CLAUDE.md).

## Commands

| Command            | What it does                                                                     |
| ------------------ | -------------------------------------------------------------------------------- |
| `npm run dev`      | Dev server on http://localhost:5173, `/api` proxied to the backend               |
| `npm run build`    | Type-check, then production build into `dist/`                                   |
| `npm run lint`     | ESLint 9 (flat config, typescript-eslint, `no-explicit-any` is error)            |
| `npm run format`   | Prettier (with the Tailwind class sorter)                                        |
| `npm test`         | Vitest + Testing Library + axe                                                   |
| `npm run test:e2e` | Playwright against the production build (`npx playwright install chromium` once) |
| `npm run gen:api`  | Regenerate `src/types/api.generated.ts` from the running backend                 |

Before pushing: `npm run lint && npm run build && npm test`. A pre-commit hook (husky + lint-staged)
lints and formats staged files; it is installed by `npm install` and lives in `.husky/`.

## Running against the backend

```bash
cp .env.example .env.local     # VITE_API_URL=http://localhost:8080
npm run dev
```

The browser only ever talks to its own origin. `vite dev` and `vite preview` forward `/api/*` to
`VITE_API_URL`; in production Nginx does the same. This is deliberate: the refresh token is an
`HttpOnly; SameSite=Strict` cookie scoped to `/api/v1/auth`, which only works same-site, and there
is no CORS to configure. `VITE_API_URL` is therefore a dev-proxy target, not a value baked into the
bundle, and must never hold a secret.

## Structure

```
src/
  features/            one folder per area; pages are default exports, lazy-loaded
    landing/  auth/  superadmin/  community-admin/
      pages/           route components (community-admin/public: /join and /pay, no login)
      queryKeys.ts     typed query-key factories for the feature
  shared/{components,hooks,utils}   cross-feature UI; components/layout holds the shells
  lib/                 axios client, ApiError, errors, money, dates, queryClient, session
  routes/              route table, ProtectedRoute, GuestOnlyRoute, paths
  store/authStore.ts   in-memory session (never localStorage)
  types/               API DTOs mirrored from the backend
  test/                setup, mock API, fixtures, axe helper
```

Import with the `@` alias (`@/lib/money`).

## Things to know

- **Auth.** The access token lives only in `authStore` (memory). A reload calls `POST /auth/refresh`
  then `GET /auth/me` (`bootstrapSession`); route guards show a spinner until that settles. A 401
  triggers one shared refresh for all in-flight requests, each retried once; if refresh fails the
  session is cleared and the router moves to `/login`. Refreshes are serialised across tabs with the
  Web Locks API, because the server revokes a session when a rotated refresh token is replayed.
- **Errors.** Every failed call rejects with `ApiError` (`status`, `code`, `detail`, `fieldErrors`,
  `retryAfterSeconds`). Show `describeError(error)` from `lib/errors.ts`, never `detail` directly.
  A test fails if the backend adds an `ErrorCode` without friendly copy here.
- **Money** is a decimal string end to end. Use `lib/money.ts` (bigint minor units) for arithmetic
  and `formatMoney` (Intl `en-IN`) for display. Never `Number(amount)`.
- **Dates.** `IsoInstant` values display in IST; `IsoDate` values (due dates) display as the day they
  name. Use `lib/dates.ts`; do not call `new Date("2026-04-08")`.
- **API types.** `src/types/*.ts` are hand-mirrored from the backend DTOs and cover auth, members,
  invites, billing, ledger, dashboard, complaints, support, announcements, communities, plans,
  subscriptions, leads, settings, imports and audit. Not yet mirrored: public plans, platform stats,
  email usage, data export and report endpoints. `npm run gen:api` (backend running with the `local`
  profile, which enables springdoc) writes the complete, authoritative set to
  `src/types/api.generated.ts`; prefer it over the hand-written file as you wire each endpoint.
- **Fonts** (Inter, Playfair Display, Cinzel) are self-hosted through Fontsource: no request leaves
  the origin for fonts.

## Serving the build with Nginx

`npm run build` writes `dist/`. Every file under `dist/assets/` has a content hash in its name, so it
can be cached forever; `index.html` must not be cached. Source maps are emitted as `*.map` files with
no `sourceMappingURL` comment (`build.sourcemap: 'hidden'`): upload them to the error tracker and do
not copy them into the image or serve them.

```nginx
root /usr/share/nginx/html;

location /api/ {
  proxy_pass http://api:8080;       # same origin: the SameSite=Strict refresh cookie needs it
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
}
location /assets/ {
  add_header Cache-Control "public, max-age=31536000, immutable";
  try_files $uri =404;
}
location ~* \.map$ { return 404; }
location / {
  add_header Cache-Control "no-cache";
  try_files $uri /index.html;       # SPA fallback
}
```
