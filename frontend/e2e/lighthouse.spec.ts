import { chromium, expect, test } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { respond, type Role } from './support/fakeApi.ts'

/**
 * Lighthouse (mobile, simulated slow 4G) on four pages: the landing page, the sign-in page and the
 * two dashboards. The production build is served by `vite preview`; the API is the small stand-in
 * from support/fakeApi.ts, so the numbers measure the front end, not a backend.
 *
 * Run it alone and one test at a time (parallel tests would disturb the timings):
 *   npm run lighthouse
 * Each full report is written to reports/lighthouse/<page>.json, and a summary to
 * reports/lighthouse/summary.md.
 *
 * Thresholds: the landing page has the strict, agreed targets (performance 90, accessibility 95,
 * SEO 95). The app pages are behind a sign-in and told to stay out of search engines, so SEO is not
 * a target for them; they must still be fast and accessible.
 */
test.describe.configure({ mode: 'serial' })
test.skip(
  !process.env.LIGHTHOUSE,
  'set LIGHTHOUSE=1 (npm run lighthouse) to run the Lighthouse audits',
)

interface Target {
  name: string
  path: string
  role: Role
  min: { performance: number; accessibility: number; 'best-practices': number; seo?: number }
}

const TARGETS: Target[] = [
  {
    name: 'landing',
    path: '/',
    role: 'GUEST',
    min: { performance: 90, accessibility: 95, 'best-practices': 95, seo: 95 },
  },
  {
    name: 'login',
    path: '/login',
    role: 'GUEST',
    min: { performance: 90, accessibility: 95, 'best-practices': 95 },
  },
  {
    name: 'superadmin-dashboard',
    path: '/superadmin/dashboard',
    role: 'SUPER_ADMIN',
    min: { performance: 90, accessibility: 95, 'best-practices': 95 },
  },
  {
    name: 'community-dashboard',
    path: '/admin/dashboard',
    role: 'COMMUNITY_ADMIN',
    min: { performance: 90, accessibility: 95, 'best-practices': 95 },
  },
]

const PORT = 9333
/** The stand-in API listens here; playwright.config.ts points the preview server's /api proxy at it. */
const FAKE_API_PORT = 8099
let currentRole: Role = 'GUEST'
// Lighthouse drives the browser through its own session, so Playwright's route mocks would not
// apply to it. A real (tiny) HTTP server answers instead, and the page talks to it through the
// preview server's /api proxy exactly as it would to the real backend.
const fakeServer = createServer((request, response) => {
  const path = new URL(request.url ?? '/', 'http://x').pathname.replace('/api/v1', '')
  const { status, body } = respond(currentRole, request.method ?? 'GET', path)
  response.writeHead(status, { 'content-type': 'application/json' })
  response.end(JSON.stringify(body))
})
test.beforeAll(async () => {
  await new Promise<void>((resolve) => fakeServer.listen(FAKE_API_PORT, '127.0.0.1', resolve))
})
test.afterAll(() => {
  fakeServer.close()
})
const summary: string[] = []

test.afterAll(() => {
  if (summary.length === 0) return
  mkdirSync('reports/lighthouse', { recursive: true })
  writeFileSync(
    'reports/lighthouse/summary.md',
    [
      '| Page | Performance | Accessibility | Best practices | SEO | LCP | CLS | TBT |',
      '|---|---|---|---|---|---|---|---|',
      ...summary,
    ].join('\n') + '\n',
  )
})

for (const target of TARGETS) {
  test(`lighthouse: ${target.name}`, async ({ baseURL }) => {
    test.setTimeout(180_000)
    const browser = await chromium.launch({ args: [`--remote-debugging-port=${PORT}`] })
    try {
      currentRole = target.role
      const context = await browser.newContext({ serviceWorkers: 'block' })
      await context.newPage()

      const { default: lighthouse } = await import('lighthouse')
      const url = new URL(target.path, baseURL).toString()
      const result = await lighthouse(url, {
        port: PORT,
        output: 'json',
        logLevel: 'error',
        formFactor: 'mobile',
        screenEmulation: {
          mobile: true,
          width: 412,
          height: 823,
          deviceScaleFactor: 1.75,
          disabled: false,
        },
        onlyCategories: ['performance', 'accessibility', 'best-practices', 'seo'],
      })
      if (!result) throw new Error('Lighthouse returned no result')

      mkdirSync('reports/lighthouse', { recursive: true })
      writeFileSync(`reports/lighthouse/${target.name}.json`, JSON.stringify(result.lhr, null, 2))

      const score = (id: string) => Math.round((result.lhr.categories[id]?.score ?? 0) * 100)
      const audit = (id: string) => result.lhr.audits[id]?.displayValue ?? ''
      const scores = {
        performance: score('performance'),
        accessibility: score('accessibility'),
        'best-practices': score('best-practices'),
        seo: score('seo'),
      }
      summary.push(
        `| ${target.name} | ${scores.performance} | ${scores.accessibility} | ${scores['best-practices']} | ${scores.seo} | ${audit('largest-contentful-paint')} | ${audit('cumulative-layout-shift')} | ${audit('total-blocking-time')} |`,
      )
      process.stdout.write(`${target.name}: ${JSON.stringify(scores)}
`)

      for (const [category, minimum] of Object.entries(target.min)) {
        expect(
          scores[category as keyof typeof scores],
          `${target.name} ${category} (needs ${minimum})`,
        ).toBeGreaterThanOrEqual(minimum)
      }
    } finally {
      await browser.close()
    }
  })
}
