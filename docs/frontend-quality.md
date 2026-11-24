# Frontend quality: accessibility, performance, resilience, PWA

What the production-polish pass (prompt F6) added, how to run each check, and the latest results.
Raw reports are generated into `frontend/reports/` (git-ignored) by the commands below and by CI,
which uploads them as build artifacts.

## Checks and how to run them (from `frontend/`)

| What | Command | Fails when |
|---|---|---|
| Lint, zero warnings | `npm run lint` | any warning or error |
| Unit and component tests (axe on every shared component) | `npm test` | any test fails |
| Type check and production build | `npm run build` | a type error |
| Initial bundle budget | `npm run check:bundle` (after a build) | the first signed-in screen needs more than 250 KB gzip |
| Bundle treemap | `npm run analyze` | never; writes `reports/bundle.html` |
| End-to-end, axe on every route, responsive, PWA | `npx playwright test` | any serious or critical axe violation, sideways scroll, a touch target under 44 px, and so on |
| Lighthouse (mobile) | `npm run lighthouse` | a score under its threshold |

CI (`.github/workflows/frontend-ci.yml`) runs all of them: lint, tests, build and bundle budget in one
job; the Playwright suite and Lighthouse in two more.

## Latest results

**Accessibility (axe, WCAG 2.2 A and AA plus best practices), 31 routes**, public, sign-in, member
link pages, all 10 super admin pages and all 10 community admin pages: **0 serious, 0 critical**.
One moderate finding remains: axe's "region" rule on the landing page flags the skip link, which by
design sits before the first landmark.

**Lighthouse, mobile, simulated slow 4G** (production build, stand-in API):

| Page | Performance | Accessibility | Best practices | SEO | LCP | CLS | TBT |
|---|---|---|---|---|---|---|---|
| Landing | 100 | 100 | 100 | 100 | 0.8 s | 0 | 0 ms |
| Sign in | 90 | 100 | 96 | 66 | 3.6 s | 0 | 0 ms |
| Super admin dashboard | 98 | 100 | 100 | 63 | 0.8 s | 0.046 | 160 ms |
| Community dashboard | 99 | 100 | 100 | 63 | 0.8 s | 0.034 | 110 ms |

Thresholds: the landing page must score performance 90, accessibility 95, SEO 95. The app pages
must score performance 90, accessibility 95, best practices 95. Their SEO score is low on purpose:
they are sign-in pages told to stay out of search engines (`is-crawlable`).
Notes: the sign-in page is at the performance floor (90) and is the one to watch; its best-practices
dip is the browser logging the expected 401 from the first-visit session check as a console error.

**Bundle:** first signed-in screen, gzip: community admin 236 KB, super admin 233 KB (budget 250 KB).
Charts, the rich text editor and the payment form are lazy chunks and are not counted.

**Responsive:** 13 pages at 360, 768, 1024 and 1440 px never scroll sideways; at 360 px every button,
field and standalone link is at least 44 by 44 px; 320 px (200% zoom) reflows.

## What was added

- **Resilience.** An offline banner (a polite live region). Friendly 5xx and 429 messages with the
  wait time (already in `lib/errors.ts`, now covered by an e2e test). In-memory form drafts
  (`lib/drafts.ts`): when a session ends mid-form the person signs in and finds the sheet open with
  their text. Drafts are never written to storage, are dropped on a sign-out the person chose, and are
  never shown to a different account. Network error toasts are de-duplicated by error code.
  Wired into: new complaint, new member, new entry, both announcement composers.
- **Focus.** Dialogs and sheets opened from state (not from a Radix trigger) now give focus back to
  the button that opened them (`shared/hooks/useReturnFocus.ts`). Before, focus fell to the top of the page.
- **Accessibility fixes found by the sweep.** Charts no longer expose focusable parts inside an
  `aria-hidden` wrapper; the lead board scroller is keyboard reachable; error screens have a `main`
  landmark; the account menu button's name matches its visible text; empty-state headings no longer skip a level.
- **Performance.** `RecordPaymentSheet` is lazy on the super admin dashboard (took 45 KB gzip off its first load). Route prefetch on hover
  and focus of a nav link, plus the next few pages when the browser is idle (not on data saver or 2G).
  The sign-in page's chunks are fetched alongside the entry script. The DataTable renders only the visible
  rows once a page has more than 50 (with `aria-rowcount` and `aria-rowindex`).
- **PWA.** `public/sw.js` caches `/assets/*`, `/icons/*`, `/brand/*` and the manifest, and the HTML shell for offline
  opening. It never touches `/api/`. Install and "new version" prompts are in `lib/pwa.ts`. Serve `sw.js` with
  `Cache-Control: no-cache` in Nginx so updates reach people.
- **i18n.** `react-i18next`, English only (`src/i18n/en.ts`). The app shell strings are migrated; page copy is not
  (see below). Layout uses logical CSS (`ms-`, `pe-`, `text-start`, `start-0`) throughout, so a right-to-left
  language needs a locale file and `dir="rtl"`, not a rewrite.
- **Error reporting.** Sentry, off unless `VITE_SENTRY_DSN` is set, and then loaded lazily. No replay, no tracing,
  no console or network breadcrumbs. Everything sent goes through `lib/scrub.ts` (tested): emails, phone numbers,
  tokens and query strings are removed, and user, cookie, header and body fields are dropped.

## Known gaps

- **Page copy is not yet translated into i18n keys.** The shell, offline banner, 404 and install prompts are; the
  roughly thousand strings inside pages and forms are still literals. Move a page over by replacing literals with
  `t('area.key')`. This is mechanical but large, and was not done in this pass.
- **Drafts cover six forms**, not every form.
- **Moderate axe notes** (see above) are accepted.
- **Lighthouse measures the front end against a stand-in API.** Run it against a deployed environment before launch.
- The sweep uses mostly empty data. Pages with long lists, many badges or open dialogs should get a second look with real data.
