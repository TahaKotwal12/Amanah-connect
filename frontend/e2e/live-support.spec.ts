import { expect, test, type Page } from '@playwright/test'
import { freshTotp } from './support/totp.ts'

/**
 * The support inbox against the REAL backend. A community owner (seeded account) opens a
 * conversation through the API, then the seeded super admin answers it in the browser, and the
 * owner reads the reply back through the API.
 *
 *   E2E_LIVE=1 E2E_DEV_SEED_PASSWORD=<password> E2E_BASE_URL=http://localhost:5174 \
 *     npx playwright test e2e/live-support.spec.ts --workers=1
 *
 * It leaves one RESOLVED conversation "Live support <time>" behind. Attachments are not covered:
 * they need the file store (MinIO locally).
 */
const password = process.env.E2E_DEV_SEED_PASSWORD
test.skip(!process.env.E2E_LIVE || !password, 'set E2E_LIVE=1 and E2E_DEV_SEED_PASSWORD')
test.setTimeout(120_000)

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

test('a community asks, the platform answers in the inbox, the community sees the reply', async ({
  page,
  request,
}) => {
  // The owner (a community admin) starts a conversation, through the API.
  const login = await request.post('/api/v1/auth/login', {
    data: { email: 'owner@dev-seed.example.test', password },
  })
  expect(login.status()).toBe(200)
  const ownerToken = ((await login.json()) as { accessToken: string }).accessToken
  const subject = `Live support ${Date.now()}`
  const created = await request.post('/api/v1/community/support/threads', {
    headers: { Authorization: `Bearer ${ownerToken}` },
    data: { subject, body: 'Automated live test: please reply.' },
  })
  expect(created.status(), await created.text()).toBe(201)
  const threadId = ((await created.json()) as { id: string }).id

  // The super admin finds it, takes it, answers and resolves it.
  await signInAsSuperAdmin(page)
  await page.goto('/superadmin/support')
  const row = page
    .getByRole('list', { name: 'Conversations' })
    .getByRole('button', { name: new RegExp(subject) })
  await expect(row).toBeVisible(SLOW)
  await expect(row).toContainText('Unassigned')
  await row.click()

  const log = page.getByRole('log', { name: 'Messages' })
  await expect(log.getByText('Automated live test: please reply.')).toBeVisible(SLOW)

  await page.getByRole('button', { name: 'Assign to me' }).click()
  await expect(page.getByRole('button', { name: 'Unassign', exact: true })).toBeVisible(SLOW)

  await page.getByLabel('Reply').fill('Thanks, this is the platform team answering.')
  await page.getByRole('button', { name: 'Send reply' }).click()
  await expect(log.getByText('Thanks, this is the platform team answering.')).toBeVisible(SLOW)
  await expect(log.getByText(/\(support\)/)).toBeVisible()

  await page.getByRole('button', { name: 'Resolve', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Reopen' })).toBeVisible(SLOW)

  // The community's own view of the same thread has the reply.
  const messages = await request.get(
    `/api/v1/community/support/threads/${threadId}/messages?after=0`,
    {
      headers: { Authorization: `Bearer ${ownerToken}` },
    },
  )
  expect(messages.status()).toBe(200)
  const bodies = ((await messages.json()) as { items: Array<{ body: string; side: string }> }).items
  expect(bodies.map((m) => m.body)).toContain('Thanks, this is the platform team answering.')
  expect(bodies.find((m) => m.body.startsWith('Thanks'))?.side).toBe('PLATFORM')
})
