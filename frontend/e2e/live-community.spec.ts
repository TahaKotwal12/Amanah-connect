import { expect, test, type Page } from '@playwright/test'

/**
 * The community admin screens against the REAL backend, as the seeded owner of the "Dev Seed
 * Community" (docs/dev-seed.md). Members have no login: the public pages are opened without one.
 *
 *   E2E_LIVE=1 E2E_DEV_SEED_PASSWORD=<password> E2E_BASE_URL=http://localhost:5174 \
 *     npx playwright test e2e/live-community.spec.ts --workers=1
 *
 * It writes: two members ("Live Member <time>", "Live Joiner <time>"), one invite link and one
 * registration. Both members are deactivated again at the end, so they stay in the list as inactive
 * (and still count towards the plan's member limit).
 */
const password = process.env.E2E_DEV_SEED_PASSWORD
test.skip(!process.env.E2E_LIVE || !password, 'set E2E_LIVE=1 and E2E_DEV_SEED_PASSWORD')
test.describe.configure({ mode: 'serial' })
test.setTimeout(120_000)

const SLOW = { timeout: 20_000 } // the development database is remote

async function signIn(page: Page, local: string) {
  await page.goto('/login')
  await page.getByLabel(/^Email/).fill(`${local}@dev-seed.example.test`)
  await page.getByLabel(/^Password/).fill(password ?? '')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/admin\/dashboard$/, SLOW)
}

test('the dashboard and the members list load real data, with the plan meter', async ({ page }) => {
  await signIn(page, 'owner')
  await expect(page.getByRole('region', { name: 'Key numbers' })).toBeVisible(SLOW)
  await expect(
    page.getByRole('region', { name: 'Key numbers' }).getByText('Active members'),
  ).toBeVisible()
  await expect(page.getByRole('table', { name: 'Billed and collected by month' })).toBeAttached(
    SLOW,
  )
  await expect(page.getByRole('alert')).toHaveCount(0)

  await page.getByRole('link', { name: 'Members', exact: true }).first().click()
  await expect(page.getByRole('heading', { level: 1, name: 'Members' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Plan limit for members' })).toBeVisible(SLOW)
  await expect(page.getByRole('table', { name: 'Members' })).toBeVisible(SLOW)
})

test('add a member, open their page, then deactivate them', async ({ page }) => {
  await signIn(page, 'owner')
  const name = `Live Member ${Date.now()}`
  await page.goto('/admin/members?new=1')

  const sheet = page.getByRole('dialog', { name: 'Add a member' })
  await sheet.getByLabel(/^Full name/).fill(name)
  await sheet
    .getByLabel('Email', { exact: true })
    .fill(`live-member-${Date.now()}@dev-seed.example.test`)
  await sheet.getByRole('button', { name: 'Add member' }).click()
  await expect(sheet).toBeHidden(SLOW)

  await page.getByRole('searchbox', { name: 'Search members' }).fill(name)
  await page.getByRole('link', { name }).first().click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible(SLOW)
  await page.getByRole('tab', { name: 'Communication' }).click()
  await expect(page.getByRole('heading', { name: 'Emails sent to this member' })).toBeVisible()
  await page.getByRole('tab', { name: 'Dues statement' }).click()
  await expect(page.getByText('No bills yet')).toBeVisible(SLOW)

  await page.getByRole('button', { name: 'Deactivate' }).click()
  const dialog = page.getByRole('dialog', { name: `Deactivate ${name}?` })
  await dialog.getByLabel(/^Reason/).fill('Automated live test')
  await dialog.getByRole('button', { name: 'Deactivate' }).click()
  await expect(dialog).toBeHidden(SLOW)
  await expect(page.getByRole('button', { name: 'Activate' })).toBeVisible(SLOW)
})

test('create an invite link, register on the public page without signing in, then approve', async ({
  page,
  browser,
}) => {
  await signIn(page, 'owner')
  const stamp = Date.now()
  const name = `Live Joiner ${stamp}`

  await page.goto('/admin/invites')
  await page.getByRole('button', { name: 'Create invite link' }).first().click()
  await page
    .getByRole('dialog', { name: 'Create an invite link' })
    .getByRole('button', { name: 'Create link' })
    .click()
  const shown = page.getByRole('dialog', { name: 'Your invite link' })
  const link = await shown.getByLabel('Invite link').inputValue({ timeout: 20_000 })
  const token = link.split('/join/')[1] ?? ''
  expect(token.length).toBeGreaterThan(10)
  await expect(shown.getByRole('img', { name: /QR code to register with/ })).toBeVisible()
  await shown.getByRole('button', { name: 'Done' }).click()

  // A fresh browser context: nobody is signed in there.
  const visitorContext = await browser.newContext({ baseURL: process.env.E2E_BASE_URL })
  const visitor = await visitorContext.newPage()
  await visitor.goto(`/join/${token}`)
  await expect(visitor.getByRole('heading', { level: 1, name: /^Join / })).toBeVisible(SLOW)
  await visitor.getByLabel(/^Full name/).fill(name)
  await visitor.getByLabel(/^Email/).fill(`live-joiner-${stamp}@dev-seed.example.test`)
  await visitor.getByRole('button', { name: 'Send my registration' }).click()
  await expect(
    visitor.getByRole('heading', { level: 1, name: 'Thank you, your registration was received' }),
  ).toBeVisible(SLOW)
  await visitorContext.close()

  await page.goto('/admin/invites?tab=registrations')
  const approve = page.getByRole('button', { name: `Approve ${name}` })
  await expect(approve).toBeVisible(SLOW)
  await approve.click()
  await expect(approve).toBeHidden(SLOW)

  await page.goto('/admin/members')
  await page.getByRole('searchbox', { name: 'Search members' }).fill(name)
  await page.getByRole('link', { name }).first().click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible(SLOW)
  await page.getByRole('button', { name: 'Deactivate' }).click()
  const dialog = page.getByRole('dialog', { name: `Deactivate ${name}?` })
  await dialog.getByLabel(/^Reason/).fill('Automated live test')
  await dialog.getByRole('button', { name: 'Deactivate' }).click()
  await expect(dialog).toBeHidden(SLOW)
})

test('a suspended community shows why and cannot be written to', async ({ page }) => {
  await signIn(page, 'owner.suspended')
  await expect(page.getByRole('alert').first()).toContainText('This community is suspended', SLOW)
  await expect(page.getByRole('button', { name: 'Add member' })).toBeDisabled(SLOW)
  await page.goto('/admin/members')
  // Wait for the notice first: until the plan has loaded, the page cannot know it is suspended.
  await expect(page.getByRole('alert').first()).toContainText('This community is suspended', SLOW)
  await expect(page.getByRole('button', { name: 'Add member' })).toBeDisabled(SLOW)
  await expect(page.getByRole('button', { name: 'Export CSV' })).toBeEnabled()
})
