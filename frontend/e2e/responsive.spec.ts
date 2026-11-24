import { expect, test, type Page } from '@playwright/test'
import { fakeApi } from './support/fakeApi.ts'
import { ROUTES } from './support/routes.ts'

/**
 * Responsive QA at the four widths the design is checked at. For each page and width:
 *   - the page never scrolls sideways (wide tables scroll inside their own region)
 *   - on touch-sized screens every button, field and standalone link is at least 44 by 44 px
 *   - at 200% zoom (a 720 px wide viewport at 360 CSS px scale) content still reflows
 */
const WIDTHS = [360, 768, 1024, 1440] as const
const TOUCH_MIN = 44

const PAGES = [
  'landing',
  'login',
  'forgot-password',
  'superadmin-dashboard',
  'superadmin-communities',
  'superadmin-plans',
  'superadmin-leads',
  'admin-dashboard',
  'admin-members',
  'admin-billing',
  'admin-budget',
  'admin-complaints',
  'admin-settings',
]
const cases = ROUTES.filter((route) => PAGES.includes(route.name))

async function settle(page: Page) {
  await page.waitForLoadState('networkidle')
  await expect(page.locator('[role="status"]:has-text("Loading")')).toHaveCount(0, {
    timeout: 10_000,
  })
}

/** Elements a finger has to hit that are smaller than the minimum. Inline links in prose are exempt. */
async function smallTargets(page: Page): Promise<string[]> {
  return page.evaluate((min) => {
    const found: string[] = []
    const selector =
      'button, [role="button"], [role="tab"], [role="menuitem"], input:not([type="hidden"]), select, textarea, a[href]'
    for (const el of Array.from(document.querySelectorAll<HTMLElement>(selector))) {
      const style = getComputedStyle(el)
      if (style.visibility === 'hidden' || style.display === 'none') continue
      if (el.closest('[aria-hidden="true"], [hidden], [inert]')) continue
      const box = el.getBoundingClientRect()
      if (box.width === 0 || box.height === 0) continue // not rendered (or screen-reader only)
      if (el.classList.contains('skip-link')) continue
      // A link that sits inside a sentence follows WCAG's "inline" exception.
      if (
        el.tagName === 'A' &&
        el.closest('p, li, dd, td, figcaption') &&
        style.display === 'inline'
      )
        continue
      // Checkboxes, radios and switches have a larger label that takes the tap.
      const type = (el as HTMLInputElement).type
      if ((type === 'checkbox' || type === 'radio') && el.closest('label')) continue
      if (el.getAttribute('role') === 'switch' || el.getAttribute('role') === 'checkbox') {
        if (el.closest('label')) continue
      }
      if (box.width < min - 0.5 || box.height < min - 0.5) {
        const name = (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 40)
        found.push(
          `${el.tagName.toLowerCase()} "${name}" ${Math.round(box.width)}x${Math.round(box.height)}`,
        )
      }
    }
    return found
  }, TOUCH_MIN)
}

for (const route of cases) {
  for (const width of WIDTHS) {
    test(`${route.name} at ${width}px: no sideways scroll${width < 768 ? ', 44px targets' : ''}`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 800 })
      await fakeApi(page, route.role)
      await page.goto(route.path)
      await settle(page)

      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }))
      expect(
        overflow.scrollWidth,
        `the page is ${overflow.scrollWidth}px wide in a ${overflow.clientWidth}px window`,
      ).toBeLessThanOrEqual(overflow.clientWidth)

      if (width < 768) expect(await smallTargets(page)).toEqual([])
    })
  }
}

test('reflows at 200% zoom: a 320px-wide window does not scroll sideways', async ({ page }) => {
  // 200% zoom on a 640px desktop window leaves 320 CSS pixels: WCAG 1.4.10 reflow.
  await page.setViewportSize({ width: 320, height: 640 })
  for (const route of cases.filter((c) =>
    ['landing', 'login', 'admin-dashboard'].includes(c.name),
  )) {
    await fakeApi(page, route.role)
    await page.goto(route.path)
    await settle(page)
    const { scrollWidth, clientWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(scrollWidth, route.name).toBeLessThanOrEqual(clientWidth)
    await page.unrouteAll()
  }
})
