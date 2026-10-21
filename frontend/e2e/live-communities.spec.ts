import { expect, test, type Page } from '@playwright/test'
import { freshTotp } from './support/totp.ts'

/**
 * The communities screens against the REAL backend, as the seeded super admin (docs/dev-seed.md).
 *
 *   E2E_LIVE=1 E2E_DEV_SEED_PASSWORD=<password> E2E_BASE_URL=http://localhost:5173 \
 *     npx playwright test e2e/live-communities.spec.ts --workers=1
 *
 * The create test really creates a community called "Live E2E <time>" with an owner at
 * @dev-seed.example.test (the invitation email is queued), then archives it. It leaves that
 * archived community and one invited user behind.
 */
const password = process.env.E2E_DEV_SEED_PASSWORD
test.skip(!process.env.E2E_LIVE || !password, 'set E2E_LIVE=1 and E2E_DEV_SEED_PASSWORD')
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000) // includes waiting out a one-time-code step between sign-ins

const SLOW = { timeout: 20_000 } // the development database is remote

async function signInAsSuperAdmin(page: Page) {
  await page.goto('/login')
  await page.getByLabel(/^Email/).fill('superadmin.2fa@dev-seed.example.test')
  await page.getByLabel(/^Password/).fill(password ?? '')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/login\/2fa$/, SLOW)
  await page.getByRole('group', { name: 'Authenticator code' }).getByRole('textbox').first().click()
  await page.keyboard.type(await freshTotp())
  await expect(page).toHaveURL(/\/superadmin\/dashboard$/, SLOW)
}

test('the seeded communities list, filter, open, and the support view is logged', async ({
  page,
}) => {
  await signInAsSuperAdmin(page)
  await page.goto('/superadmin/communities')
  const table = page.getByRole('table', { name: 'Communities' })
  await expect(table.getByRole('link', { name: 'Dev Seed Community', exact: true })).toBeVisible(
    SLOW,
  )

  await page.getByRole('searchbox', { name: 'Search communities' }).fill('Dev Seed')
  await expect(page).toHaveURL(/q=Dev\+Seed|q=Dev%20Seed/, SLOW)
  await table.getByRole('link', { name: 'Dev Seed Community', exact: true }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Dev Seed Community' })).toBeVisible(
    SLOW,
  )

  await page.getByRole('tab', { name: 'Plan and subscription' }).click()
  await expect(page.getByRole('heading', { name: 'Payment history' })).toBeVisible()
  await page.getByRole('tab', { name: 'Activity' }).click()
  await expect(page.getByRole('heading', { name: 'Recent activity' })).toBeVisible()

  await page.getByRole('link', { name: 'Support view' }).click()
  await expect(
    page.getByText('You are viewing community data (this access is logged).'),
  ).toBeVisible(SLOW)
  await expect(page.getByRole('heading', { name: 'Right now' })).toBeVisible(SLOW)
})

test('create a community through the wizard, find it, then archive it', async ({ page }) => {
  await signInAsSuperAdmin(page)
  const name = `Live E2E ${Date.now()}`
  await page.goto('/superadmin/communities?new=1')

  const wizard = page.getByRole('dialog', { name: 'New community' })
  await wizard.getByLabel(/^Community name/).fill(name)
  await wizard.getByRole('button', { name: 'Next' }).click()
  await wizard.getByRole('radio').first().check({ timeout: 20_000 })
  await wizard.getByRole('button', { name: 'Next' }).click()
  await wizard.getByLabel(/^Owner’s name/).fill('Live E2E Owner')
  await wizard.getByLabel(/^Owner’s email/).fill(`live-e2e-${Date.now()}@dev-seed.example.test`)
  await wizard.getByRole('button', { name: 'Next' }).click()
  await wizard.getByRole('button', { name: 'Create community' }).click()

  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible(SLOW)
  await page.getByRole('tab', { name: 'Admin and contact' }).click()
  await expect(page.getByRole('button', { name: 'Send the invitation again' })).toBeVisible(SLOW)

  await page.getByRole('button', { name: 'Actions' }).click()
  await page.getByRole('menuitem', { name: 'Archive' }).click()
  const dialog = page.getByRole('dialog', { name: `Archive ${name}?` })
  await dialog.getByLabel(/^Reason/).fill('Automated live test')
  await dialog.getByRole('button', { name: 'Archive' }).click()
  await expect(dialog).toBeHidden(SLOW)
  await expect(page.getByText('Archived').first()).toBeVisible(SLOW)
})
