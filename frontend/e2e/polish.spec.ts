import { expect, test, type Page } from '@playwright/test'
import { COMMUNITY_ADMIN_ME, fakeApi } from './support/fakeApi.ts'

/**
 * The production-polish behaviours that a unit test cannot show: keyboard and focus, reduced
 * motion, the offline banner, friendly server errors, and a session that expires mid-form.
 */

async function settle(page: Page) {
  await page.waitForLoadState('networkidle')
  await expect(page.locator('[role="status"]:has-text("Loading")')).toHaveCount(0)
}

test.describe('keyboard and focus', () => {
  test('the skip link is the first tab stop, appears on focus, and moves focus to the content', async ({
    page,
  }) => {
    await fakeApi(page, 'COMMUNITY_ADMIN')
    await page.goto('/admin/dashboard')
    await settle(page)

    await page.keyboard.press('Tab')
    const skip = page.getByRole('link', { name: 'Skip to main content' })
    await expect(skip).toBeFocused()
    await expect(skip).toBeVisible()
    await page.keyboard.press('Enter')
    await expect(page.locator('#main')).toBeFocused()
  })

  test('a sheet takes focus when it opens, keeps it inside, and gives it back on Escape', async ({
    page,
  }) => {
    await fakeApi(page, 'COMMUNITY_ADMIN')
    await page.goto('/admin/members')
    await settle(page)

    const opener = page.getByRole('button', { name: 'Add member' }).first()
    await opener.focus()
    await page.keyboard.press('Enter')
    const sheet = page.getByRole('dialog', { name: /member/i })
    await expect(sheet).toBeVisible()
    await expect(sheet.locator(':focus')).toHaveCount(1)

    // Tab must stay inside the dialog however many times it is pressed.
    for (let i = 0; i < 25; i += 1) {
      await page.keyboard.press('Tab')
      expect(await sheet.evaluate((el) => el.contains(document.activeElement))).toBe(true)
    }

    await page.keyboard.press('Escape')
    await expect(sheet).toBeHidden()
    await expect(opener).toBeFocused()
  })

  test('every focused control shows a visible focus ring', async ({ page }) => {
    await fakeApi(page, 'COMMUNITY_ADMIN')
    await page.goto('/admin/dashboard')
    await settle(page)

    const unringed: string[] = []
    for (let i = 0; i < 14; i += 1) {
      await page.keyboard.press('Tab')
      const probe = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement | null
        if (!el || el === document.body) return null
        const style = getComputedStyle(el)
        const outline = style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0
        const shadow = style.boxShadow !== 'none'
        return {
          name: (el.getAttribute('aria-label') ?? el.textContent ?? el.tagName).trim().slice(0, 30),
          ringed: outline || shadow,
        }
      })
      if (probe && !probe.ringed) unringed.push(probe.name)
    }
    expect(unringed).toEqual([])
  })
})

test.describe('motion', () => {
  test('animations and transitions are switched off when the person asks for less motion', async ({
    browser,
  }) => {
    const context = await browser.newContext({ reducedMotion: 'reduce' })
    const page = await context.newPage()
    await fakeApi(page, 'GUEST')
    await page.goto('/')
    await settle(page)

    const longest = await page.evaluate(() => {
      let max = 0
      for (const el of Array.from(document.querySelectorAll<HTMLElement>('*'))) {
        const style = getComputedStyle(el)
        const parse = (value: string) =>
          Math.max(
            0,
            ...value.split(',').map((part) => parseFloat(part) * (part.includes('ms') ? 1 : 1000)),
          )
        max = Math.max(max, parse(style.animationDuration), parse(style.transitionDuration))
      }
      return max
    })
    expect(longest).toBeLessThanOrEqual(1) // ms
    await context.close()
  })
})

test.describe('when things go wrong', () => {
  test('says so when the connection drops, and when it returns', async ({ page, context }) => {
    await fakeApi(page, 'COMMUNITY_ADMIN')
    await page.goto('/admin/dashboard')
    await settle(page)

    await context.setOffline(true)
    const status = page.getByTestId('connection-status')
    await expect(status).toContainText('You are offline')
    await expect(status).toHaveAttribute('aria-live', 'polite')

    await context.setOffline(false)
    await expect(status).toContainText('You are back online')
    await expect(status).toHaveText('', { timeout: 8000 })
  })

  test('a server error gets a plain message and a retry that works', async ({ page }) => {
    await fakeApi(page, 'COMMUNITY_ADMIN')
    let failing = true
    await page.route('**/api/v1/community/members?**', (route) =>
      failing
        ? route.fulfill({
            status: 503,
            contentType: 'application/problem+json',
            json: { status: 503, code: 'INTERNAL_ERROR', detail: 'db down: stack trace here' },
          })
        : route.fallback(),
    )
    await page.goto('/admin/members')

    const alert = page.getByRole('alert').first()
    await expect(alert).toContainText('Something went wrong on our side')
    // Nothing from the server's own text reaches the screen.
    await expect(page.getByText('stack trace')).toHaveCount(0)

    failing = false
    await page
      .getByRole('button', { name: /try again|retry/i })
      .first()
      .click()
    await expect(page.getByRole('cell', { name: 'Asha Rao', exact: true })).toBeVisible()
  })

  test('too many requests says how long to wait', async ({ page }) => {
    await fakeApi(page, 'COMMUNITY_ADMIN')
    await page.route('**/api/v1/community/members?**', (route) =>
      route.fulfill({
        status: 429,
        headers: { 'Retry-After': '30' },
        contentType: 'application/problem+json',
        json: { status: 429, code: 'RATE_LIMITED', detail: 'slow down' },
      }),
    )
    await page.goto('/admin/members')
    await expect(page.getByRole('alert').first()).toContainText('Too many attempts')
    await expect(page.getByRole('alert').first()).toContainText('30 seconds')
  })

  test('a session that ends mid-form keeps the draft for after sign-in', async ({ page }) => {
    let signedIn = true
    const json = (status: number, body: unknown) => ({ status, json: body })
    await fakeApi(page, 'COMMUNITY_ADMIN')
    // Replace the happy-path auth with one that can expire.
    await page.route('**/api/v1/auth/refresh', (route) =>
      route.fulfill(
        signedIn
          ? json(200, {
              accessToken: 'e2e',
              tokenType: 'Bearer',
              expiresIn: 900,
              mfaSetupRequired: false,
            })
          : json(401, { status: 401, code: 'INVALID_REFRESH_TOKEN', detail: 'expired' }),
      ),
    )
    await page.route('**/api/v1/auth/login', (route) => {
      signedIn = true
      return route.fulfill(
        json(200, {
          accessToken: 'e2e2',
          tokenType: 'Bearer',
          expiresIn: 900,
          mfaSetupRequired: false,
        }),
      )
    })
    await page.goto('/admin/complaints')
    await settle(page)

    await page.getByRole('button', { name: 'Log a complaint' }).first().click()
    const sheet = page.getByRole('dialog', { name: 'Log a complaint' })
    await sheet.getByLabel(/^Subject/).fill('Water leak in block A')

    // The session ends while the person is typing: saving is refused and the refresh fails too.
    await sheet.getByLabel(/^What happened/).fill('Water is leaking into the stairwell.')
    signedIn = false
    await page.route('**/api/v1/community/complaints', (route) =>
      route.request().method() === 'POST'
        ? route.fulfill(json(401, { status: 401, code: 'UNAUTHENTICATED', detail: 'expired' }))
        : route.fallback(),
    )
    await sheet.getByRole('button', { name: 'Log complaint' }).click()
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByText(/session ended/i)).toBeVisible()

    // Sign back in. The sheet's open state is in the address, so the person lands back in the same
    // form, and what they had typed is still there.
    await page.getByLabel(/^Email/).fill(COMMUNITY_ADMIN_ME.user.email)
    await page.getByLabel(/^Password/).fill('correct-password-1')
    await page.getByRole('button', { name: /sign in/i }).click()
    const again = page.getByRole('dialog', { name: 'Log a complaint' })
    await expect(again.getByLabel(/^Subject/)).toHaveValue('Water leak in block A')
    await expect(again.getByLabel(/^What happened/)).toHaveValue(
      'Water is leaking into the stairwell.',
    )
  })
})
