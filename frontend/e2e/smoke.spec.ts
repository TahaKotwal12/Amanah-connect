import { expect, test, type Page } from '@playwright/test'

const ME = {
  user: {
    id: '3f2b8c1e-6d7a-4c58-9a55-2d1a0e8c9b11',
    email: 'root@example.test',
    fullName: 'Platform Admin',
    role: 'SUPER_ADMIN',
    status: 'ACTIVE',
  },
  community: null,
  flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: true },
}

/** No refresh cookie: the API answers the way it does for a first-time visitor. */
async function signedOut(page: Page) {
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      json: { status: 401, code: 'INVALID_REFRESH_TOKEN', detail: 'Invalid refresh token' },
    }),
  )
}

async function signedInAsSuperAdmin(page: Page) {
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({
      status: 200,
      json: {
        accessToken: 'e2e-token',
        tokenType: 'Bearer',
        expiresIn: 900,
        mfaSetupRequired: false,
      },
    }),
  )
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ status: 200, json: ME }))
  // The pages behind the shell load their own data: empty, so none of them depends on a backend.
  await page.route('**/api/v1/admin/**', (route) =>
    route.fulfill({
      status: 200,
      json: route.request().url().endsWith('/admin/plans')
        ? []
        : { items: [], total: 0, page: 0, size: 20 },
    }),
  )
}

test('landing page boots with brand assets, tokens and PWA metadata', async ({ page }) => {
  await signedOut(page)
  await page.goto('/')

  await expect(page).toHaveTitle(/Amanah Connect/)
  await expect(
    page.getByRole('heading', { level: 1, name: /Run your community with clarity/ }),
  ).toBeVisible()

  // The logo mark in the navbar is a real, decoded image (AVIF, WebP or PNG, whichever the browser takes).
  const mark = page.getByRole('link', { name: 'Amanah Connect home' }).locator('img')
  await expect(mark).toBeVisible()
  expect(await mark.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0)

  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#07363E')
  const manifestHref = await page.locator('link[rel="manifest"]').getAttribute('href')
  const manifest = await page.request.get(manifestHref ?? '/manifest.webmanifest')
  expect(manifest.ok()).toBe(true)
  expect(((await manifest.json()) as { theme_color: string }).theme_color).toBe('#07363E')

  // Self-hosted fonts: nothing may come from a Google CDN.
  const external: string[] = []
  page.on('request', (request) => {
    if (!request.url().startsWith('http://localhost')) external.push(request.url())
  })
  await page.reload()
  await page.waitForLoadState('networkidle')
  expect(external).toEqual([])
})

test('an unknown address shows the 404 page', async ({ page }) => {
  await signedOut(page)
  await page.goto('/this/does/not/exist')
  await expect(page.getByRole('heading', { name: 'We could not find that page' })).toBeVisible()
})

test('a signed-out visitor to an admin page lands on the login page', async ({ page }) => {
  await signedOut(page)
  await page.goto('/admin/members')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible()
})

test('a returning super admin is restored from the refresh cookie and sees their area', async ({
  page,
}) => {
  await signedInAsSuperAdmin(page)
  await page.goto('/superadmin/communities')
  await expect(page.getByRole('heading', { level: 1, name: 'Communities' })).toBeVisible()
  await expect(page.getByText('Platform Admin')).toBeVisible()
  await page.getByRole('link', { name: 'Plans' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Plans' })).toBeVisible()
})

test('a community admin cannot open the super admin area', async ({ page }) => {
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({
      status: 200,
      json: { accessToken: 't', tokenType: 'Bearer', expiresIn: 900, mfaSetupRequired: false },
    }),
  )
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      status: 200,
      json: {
        ...ME,
        user: { ...ME.user, role: 'COMMUNITY_ADMIN' },
        community: { id: 'c1', name: 'Test Community', slug: 'test', status: 'ACTIVE' },
      },
    }),
  )
  await page.goto('/superadmin/dashboard')
  await expect(page).toHaveURL(/\/unauthorized$/)
})
