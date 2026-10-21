import { expect, test, type Page } from '@playwright/test'
import { freshTotp } from './support/totp.ts'

/**
 * Invites, leads, announcements and the audit trail against the REAL backend, as the seeded super
 * admin (docs/dev-seed.md).
 *
 *   E2E_LIVE=1 E2E_DEV_SEED_PASSWORD=<password> E2E_BASE_URL=http://localhost:5174 \
 *     npx playwright test e2e/live-engagement.spec.ts --workers=1
 *
 * It writes: one community "Live Invite <time>" (archived at the end) whose owner invitation is
 * revoked and sent again, and one announcement DRAFT (never sent).
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

test('leads board and audit trail load real data', async ({ page }) => {
  await signInAsSuperAdmin(page)

  await page.goto('/superadmin/leads')
  await expect(page.getByRole('region', { name: 'New leads' })).toBeVisible(SLOW)
  await expect(page.getByRole('region', { name: 'Lost leads' })).toBeVisible(SLOW)
  await expect(page.getByRole('alert')).toHaveCount(0)

  await page.goto('/superadmin/audit')
  const first = page
    .getByRole('list')
    .getByRole('button', { name: /^Show details for / })
    .first()
  await expect(first).toBeVisible(SLOW)
  await first.click()
  await expect(page.getByRole('button', { name: /^Hide details for / })).toHaveCount(1)
})

test('an announcement draft is saved and listed (and never sent)', async ({ page }) => {
  await signInAsSuperAdmin(page)
  const title = `Live draft ${Date.now()}`
  await page.goto('/superadmin/announcements')

  await page.getByRole('button', { name: 'New announcement' }).first().click()
  const sheet = page.getByRole('dialog', { name: 'New announcement' })
  await sheet.getByLabel(/^Title/).fill(title)
  await sheet.getByLabel(/^Message/).fill('Automated live test. This draft is never sent.')
  await sheet.getByRole('button', { name: 'Save draft' }).click()
  await expect(sheet).toBeHidden(SLOW)

  await expect(page.getByRole('button', { name: title }).first()).toBeVisible(SLOW)
  await page.getByRole('button', { name: title }).first().click()
  const dialog = page.getByRole('dialog', { name: title })
  await expect(dialog.getByText(/administrators in .* communities/)).toBeVisible(SLOW)
})

test('an owner invitation is listed, revoked, and sent again', async ({ page }) => {
  await signInAsSuperAdmin(page)
  const stamp = Date.now()
  const name = `Live Invite ${stamp}`
  const email = `live-invite-${stamp}@dev-seed.example.test`

  await page.goto('/superadmin/communities?new=1')
  const wizard = page.getByRole('dialog', { name: 'New community' })
  await wizard.getByLabel(/^Community name/).fill(name)
  await wizard.getByRole('button', { name: 'Next' }).click()
  await wizard.getByRole('radio').first().check(SLOW)
  await wizard.getByRole('button', { name: 'Next' }).click()
  await wizard.getByLabel(/^Owner’s name/).fill('Live Invite Owner')
  await wizard.getByLabel(/^Owner’s email/).fill(email)
  await wizard.getByRole('button', { name: 'Next' }).click()
  await wizard.getByRole('button', { name: 'Create community' }).click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible(SLOW)
  const communityUrl = page.url()

  await page.goto(`/superadmin/invites?q=${stamp}`)
  const row = page.getByRole('row').filter({ hasText: email })
  await expect(row).toBeVisible(SLOW)
  await expect(row).toContainText('Waiting')

  await row.getByRole('button', { name: /^Actions for / }).click()
  await page.getByRole('menuitem', { name: 'Revoke' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Revoke' }).click()
  await expect(page.getByRole('row').filter({ hasText: email })).toContainText('Revoked', SLOW)

  await page
    .getByRole('row')
    .filter({ hasText: email })
    .getByRole('button', { name: /^Actions for / })
    .click()
  await page.getByRole('menuitem', { name: 'Send a new invitation' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Send' }).click()
  await expect(page.getByRole('row').filter({ hasText: email })).toContainText('Waiting', SLOW)

  // Tidy up: archive the community again.
  await page.goto(communityUrl)
  await page.getByRole('button', { name: 'Actions' }).click()
  await page.getByRole('menuitem', { name: 'Archive' }).click()
  const archive = page.getByRole('dialog', { name: `Archive ${name}?` })
  await archive.getByLabel(/^Reason/).fill('Automated live test')
  await archive.getByRole('button', { name: 'Archive' }).click()
  await expect(archive).toBeHidden(SLOW)
})
