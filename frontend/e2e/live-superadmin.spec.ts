import { expect, test, type Page } from '@playwright/test'
import { freshTotp } from './support/totp.ts'

/**
 * The super admin screens against the REAL backend, signed in as the seeded super admin
 * (docs/dev-seed.md). Same switches as live-accounts.spec.ts:
 *
 *   E2E_LIVE=1 E2E_DEV_SEED_PASSWORD=<password> E2E_BASE_URL=http://localhost:5173 \
 *     npx playwright test e2e/live-superadmin.spec.ts --workers=1
 *
 * The payment test writes one subscription for the "Dev Seed Community" and then cancels it, so it
 * leaves a cancelled row (reference live-e2e-...) behind. Cancelled rows do not count as revenue.
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

test('dashboard, plans and subscriptions load real data without an error state', async ({
  page,
}) => {
  await signInAsSuperAdmin(page)

  const numbers = page.getByRole('region', { name: 'Key numbers' })
  await expect(numbers.getByText('Revenue this month')).toBeVisible(SLOW)
  await expect(numbers.getByText(/^₹[\d,]+\.\d\d$/).first()).toBeVisible(SLOW)
  await expect(page.getByRole('table', { name: 'Revenue by month' })).toBeAttached(SLOW)
  const rows = page.getByRole('table', { name: 'New communities by month' }).getByRole('row')
  await expect(rows).toHaveCount(13) // header + 12 months
  await expect(page.getByRole('alert')).toHaveCount(0)

  await page.getByRole('link', { name: 'Plans' }).first().click()
  await expect(page.getByRole('heading', { level: 1, name: 'Plans' })).toBeVisible()
  await expect(page.getByRole('heading', { level: 2, name: 'Starter' })).toBeVisible(SLOW)

  await page.getByRole('link', { name: 'Subscriptions' }).first().click()
  await expect(page.getByRole('heading', { level: 1, name: 'Subscriptions' })).toBeVisible()
  await expect(page.getByRole('table', { name: 'Subscriptions' })).toBeVisible(SLOW)
})

test('record a payment, see it in the list, then cancel it with a reason', async ({ page }) => {
  await signInAsSuperAdmin(page)
  await page.goto('/superadmin/subscriptions')
  const reference = `live-e2e-${Date.now()}`

  await page.getByRole('button', { name: 'Record payment' }).first().click()
  const sheet = page.getByRole('dialog', { name: 'Record a payment' })
  await sheet.getByRole('combobox', { name: /^Community/ }).click()
  await page.getByRole('option', { name: 'Dev Seed Community' }).first().click()
  await sheet.getByRole('combobox', { name: /^Plan/ }).click()
  await page.getByRole('option').first().click()
  await expect(sheet.getByLabel(/^Amount received/)).not.toHaveValue('')
  await sheet.getByLabel('Reference').fill(reference)
  await sheet.getByRole('button', { name: 'Record payment' }).click()
  await expect(sheet).toBeHidden(SLOW)

  const row = page.getByRole('row').filter({ hasText: reference })
  await expect(row).toBeVisible(SLOW)

  await row.getByRole('button', { name: /^Actions for / }).click()
  await page.getByRole('menuitem', { name: 'Cancel subscription' }).click()
  const dialog = page.getByRole('dialog', { name: 'Cancel this subscription?' })
  await dialog.getByLabel(/^Reason/).fill('Automated live test')
  await dialog.getByRole('button', { name: 'Cancel subscription' }).click()
  await expect(dialog).toBeHidden(SLOW)
  await expect(page.getByRole('row').filter({ hasText: reference })).toContainText(
    'Cancelled',
    SLOW,
  )
})
