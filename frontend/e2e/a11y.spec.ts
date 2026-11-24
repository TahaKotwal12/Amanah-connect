import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { fakeApi } from './support/fakeApi.ts'
import { ROUTES } from './support/routes.ts'

/**
 * Accessibility sweep: axe on every route, as the role that can open it. The build fails on any
 * serious or critical violation (WCAG 2.2 A and AA plus axe's best practices). Everything axe
 * finds is also written to reports/axe/<route>.json so lesser findings can be reviewed.
 */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']

async function settle(page: Page) {
  await page.waitForLoadState('networkidle')
  // Skeletons and spinners are not the page: wait until loading has finished.
  await expect(page.locator('[role="status"]:has-text("Loading")')).toHaveCount(0, {
    timeout: 10_000,
  })
}

for (const route of ROUTES) {
  test(`axe: ${route.name}`, async ({ page }) => {
    await fakeApi(page, route.role)
    await page.goto(route.path)
    await settle(page)

    const results = await new AxeBuilder({ page }).withTags(TAGS).analyze()
    mkdirSync('reports/axe', { recursive: true })
    writeFileSync(
      `reports/axe/${route.name}.json`,
      JSON.stringify(
        {
          url: route.path,
          passes: results.passes.length,
          violations: results.violations.map((v) => ({
            id: v.id,
            impact: v.impact,
            help: v.help,
            nodes: v.nodes.map((n) => n.target.join(' ')),
          })),
        },
        null,
        2,
      ),
    )
    const blocking = results.violations.filter(
      (v) => v.impact === 'serious' || v.impact === 'critical',
    )
    expect(
      blocking.map(
        (v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`,
      ),
    ).toEqual([])
  })
}
