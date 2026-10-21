import { expect, test } from '@playwright/test'

/**
 * Real browser against the REAL backend: no stubbed network. Opt in, so a normal `npm run test:e2e`
 * never touches a database:
 *
 *   # terminal 1: backend (local profile) on :8080     terminal 2: npm run dev  (allowed origin :5173)
 *   E2E_LIVE=1 E2E_BASE_URL=http://localhost:5173 npx playwright test e2e/live.spec.ts
 *
 * Extra opt-ins:
 *   E2E_LIVE_WRITE=1                        also submits ONE demo request (writes a lead row)
 *   E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD    enables the signed-in tests (account without 2FA)
 *
 * The backend must run the current code (PublicPlanController).
 * It must run on the dev server (:5173): the backend only accepts that origin for cookie calls.
 */
test.skip(!process.env.E2E_LIVE, 'set E2E_LIVE=1 to run against the real backend')

const adminEmail = process.env.E2E_ADMIN_EMAIL
const adminPassword = process.env.E2E_ADMIN_PASSWORD

test('the backend is reachable through the dev proxy', async ({ request }) => {
  const response = await request.get('/api/v1/ping')
  expect(response.ok()).toBe(true)
  expect(await response.json()).toMatchObject({ time: expect.any(String) })
})

test('pricing shows the real plans from GET /api/v1/public/plans', async ({ page, request }) => {
  const plans = (await (await request.get('/api/v1/public/plans')).json()) as Array<{
    name: string
  }>
  expect(plans.length).toBeGreaterThan(0)

  await page.goto('/#pricing')
  await expect(
    page.getByRole('heading', { level: 2, name: 'Simple plans, no surprises' }),
  ).toBeVisible()
  // One card per plan the backend published, named exactly as the backend names it.
  for (const plan of plans) {
    await expect(
      page.getByRole('heading', { level: 3, name: plan.name, exact: true }),
    ).toBeVisible()
  }
  await expect(page.getByText('Plans sized to your community')).toHaveCount(0) // not the fallback
  await page.getByRole('button', { name: 'Yearly' }).click()
  await expect(page.getByRole('button', { name: 'Yearly' })).toHaveAttribute('aria-pressed', 'true')
})

test('a wrong password gets the backend’s answer, shown in plain words', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel(/^Email/).fill('nobody.integration@example.invalid')
  await page.getByLabel(/^Password/).fill('definitely-not-a-password')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('alert')).toHaveText('The email or password is not correct.')
  await expect(page).toHaveURL(/\/login$/)
})

test('forgot password: the real API says 202 for an address that cannot exist, and the page reveals nothing', async ({
  page,
}) => {
  await page.goto('/forgot-password')
  await page.getByLabel(/^Email/).fill('nobody.integration@example.invalid')
  await page.getByRole('button', { name: 'Send the link' }).click()
  await expect(page.getByRole('status')).toContainText(
    'If there is an account for nobody.integration@example.invalid',
  )
})

test('a made-up reset link is refused by the backend and the page offers a new one', async ({
  page,
}) => {
  await page.goto('/reset-password?token=not-a-real-token')
  await page.getByLabel(/^New password/).fill('Correct-Horse-9')
  await page.getByLabel(/^Confirm new password/).fill('Correct-Horse-9')
  await page.getByRole('button', { name: 'Change password' }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: 'This link cannot be used' }),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: 'Send me a new link' })).toBeVisible()
})

test('a made-up invitation is refused by the backend', async ({ page }) => {
  await page.goto('/accept-invite?token=not-a-real-token')
  await page.getByLabel(/^New password/).fill('Correct-Horse-9')
  await page.getByLabel(/^Confirm new password/).fill('Correct-Horse-9')
  await page.getByRole('button', { name: 'Create my account' }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: 'This invitation cannot be used' }),
  ).toBeVisible()
})

test('the demo form shows the backend’s own validation messages', async ({ page }) => {
  await page.goto('/#demo')
  const form = page.getByRole('form', { name: 'Request a demo' })
  await form.getByLabel(/Your name/).fill('Integration Test')
  await form.getByLabel(/^Email/).fill('integration.test@example.invalid')
  await form.getByLabel(/Approximate number/).fill('5')
  // The browser accepts this; send an out-of-range value the backend rejects but the form allows.
  await page.route('**/api/v1/public/leads', (route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>
    return route.continue({ postData: JSON.stringify({ ...body, size: 0 }) })
  })
  await form.getByRole('button', { name: 'Request a demo' }).click()
  await expect(form.getByText(/must be greater than or equal to 1/)).toBeVisible()
})

test.describe('writes one lead', () => {
  test.skip(!process.env.E2E_LIVE_WRITE, 'set E2E_LIVE_WRITE=1 to submit a real demo request')

  test('the demo form submits to the real backend and confirms', async ({ page }) => {
    await page.goto('/#demo')
    const form = page.getByRole('form', { name: 'Request a demo' })
    await form.getByLabel(/Your name/).fill('Integration test (safe to delete)')
    await form.getByLabel(/^Email/).fill('integration.test@example.invalid')
    await form.getByLabel(/Community name/).fill('Frontend integration check')
    await form.getByLabel(/Approximate number/).fill('10')
    const response = page.waitForResponse((r) => r.url().endsWith('/api/v1/public/leads'))
    await form.getByRole('button', { name: 'Request a demo' }).click()
    expect((await response).status()).toBe(202)
    await expect(page.getByRole('status').filter({ hasText: 'Thank you' })).toBeVisible()
  })
})

test.describe('signed in (needs E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD)', () => {
  test.skip(!adminEmail || !adminPassword, 'set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD')

  test('login, session survives a reload through the real refresh cookie, then logout', async ({
    page,
  }) => {
    await page.goto('/admin/members')
    await expect(page).toHaveURL(/\/login$/)
    await page.getByLabel(/^Email/).fill(adminEmail ?? '')
    await page.getByLabel(/^Password/).fill(adminPassword ?? '')
    await page.getByRole('button', { name: 'Sign in' }).click()

    // Either a dashboard, the forced 2FA setup, or a second step: say which, do not guess past it.
    await expect(page).toHaveURL(/\/(admin|superadmin)\/|\/2fa\/setup$|\/login\/2fa$/, {
      timeout: 15_000,
    })
    test.skip(
      /\/login\/2fa$|\/2fa\/setup$/.test(page.url()),
      'this account needs a second factor: use one without 2FA for this test',
    )

    const where = new URL(page.url()).pathname
    await page.reload() // real POST /auth/refresh with the HttpOnly cookie, rotation and all
    await expect(page).toHaveURL(new RegExp(`${where}$`))
    await expect(page.getByRole('button', { name: /^Account menu for / })).toBeVisible()

    await page.getByRole('button', { name: /^Account menu for / }).click()
    await page.getByRole('menuitem', { name: 'Sign out' }).click()
    await expect(page).toHaveURL(/\/login$/)

    // Really signed out: the cookie was revoked, so a protected page bounces back.
    await page.goto(where)
    await expect(page).toHaveURL(/\/login$/)
  })
})
