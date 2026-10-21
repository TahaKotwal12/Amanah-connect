import { expect, test, type BrowserContext, type Route } from '@playwright/test'

/**
 * Writing and sending an announcement in a real browser, with the real rich text editor: write it,
 * check who it reaches, send a test to yourself, send it, and find it in the history with its
 * delivery numbers. The API is a small stand-in that keeps the announcements.
 */
const ME = {
  user: {
    id: 'u1',
    email: 'admin@example.test',
    fullName: 'Community Admin',
    role: 'COMMUNITY_ADMIN',
    status: 'ACTIVE',
  },
  community: { id: 'c1', name: 'Lotus Residents', slug: 'lotus', status: 'ACTIVE' },
  flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: false },
}

interface Announcement {
  id: string
  title: string
  bodyHtml: string
  audience: string
  sendEmail: boolean
  status: 'DRAFT' | 'SCHEDULED' | 'SENT'
}

async function fakeAnnouncements(context: BrowserContext) {
  const items: Announcement[] = []
  const tests: string[] = []
  const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, json: body })
  const page = <T>(list: T[]) => ({ items: list, total: list.length, page: 0, size: 20 })

  const view = (a: Announcement) => ({
    id: a.id,
    title: a.title,
    bodyHtml: a.bodyHtml,
    bodyText: a.bodyHtml.replace(/<[^>]*>/g, ''),
    audience: a.audience,
    group: null,
    memberIds: [],
    sendEmail: a.sendEmail,
    status: a.status,
    scheduledAt: null,
    sentAt: a.status === 'SENT' ? '2026-10-18T12:00:00Z' : null,
    delivery:
      a.status === 'SENT'
        ? {
            recipientsTotal: 40,
            emailsQueued: 34,
            skippedNoEmail: 4,
            skippedNoConsent: 2,
            skippedQuota: 0,
          }
        : null,
    createdBy: 'u1',
    createdAt: '2026-10-18T10:00:00Z',
    updatedAt: '2026-10-18T10:00:00Z',
  })

  await context.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/api/v1', '')
    const key = `${request.method()} ${path}`
    const body = (request.postDataJSON() ?? {}) as Record<string, string | boolean | undefined>

    if (key === 'POST /auth/refresh')
      return json(route, {
        accessToken: 'e2e',
        tokenType: 'Bearer',
        expiresIn: 900,
        mfaSetupRequired: false,
      })
    if (key === 'GET /auth/me') return json(route, ME)
    if (key === 'GET /community/plan')
      return json(route, {
        planCode: 'STARTER',
        planName: 'Starter',
        limits: { emails_per_month: 500 },
        features: {},
        usage: { members: 40, emailsThisMonth: 120 },
        communityStatus: 'ACTIVE',
        subscription: null,
        expiringWindowDays: 30,
      })
    if (key === 'GET /community/notifications/summary')
      return json(route, { unread: 0, banners: [] })
    if (key === 'GET /community/settings')
      return json(route, {
        name: 'Lotus Residents',
        logoUrl: null,
        currency: 'INR',
        memberGroupLabel: 'Group',
      })
    if (key === 'GET /community/announcements') {
      const wanted = url.searchParams.get('status')
      return json(route, page(items.filter((a) => !wanted || a.status === wanted).map(view)))
    }
    if (key === 'POST /community/announcements') {
      const made: Announcement = {
        id: `ann-${items.length + 1}`,
        title: String(body.title),
        bodyHtml: String(body.body),
        audience: String(body.audience ?? 'ALL_ACTIVE'),
        sendEmail: body.sendEmail !== false,
        status: 'DRAFT',
      }
      items.push(made)
      return json(route, view(made), 201)
    }
    const one =
      /^(GET|PATCH|POST) \/community\/announcements\/(ann-\d+)(\/preview|\/send-test|\/send)?$/.exec(
        key,
      )
    if (one) {
      const found = items.find((a) => a.id === one[2])
      if (!found) return json(route, { status: 404, code: 'NOT_FOUND', detail: 'none' }, 404)
      if (one[1] === 'PATCH') {
        if (body.title) found.title = String(body.title)
        if (body.body) found.bodyHtml = String(body.body)
        return json(route, view(found))
      }
      if (one[3] === '/preview')
        return json(route, {
          title: found.title,
          bodyHtml: found.bodyHtml,
          bodyText: '',
          audience: found.audience,
          sendEmail: found.sendEmail,
          audienceSize: 40,
          eligibleForEmail: 34,
          noEmail: 4,
          noConsent: 2,
          quotaRemaining: 380,
          wouldSkipForQuota: 0,
        })
      if (one[3] === '/send-test') {
        tests.push(found.id)
        return json(route, { sentTo: 'admin@example.test', queued: true })
      }
      if (one[3] === '/send') {
        found.status = 'SENT'
        return json(route, view(found))
      }
      return json(route, view(found))
    }
    return json(route, page([]))
  })
  return { items, tests }
}

test('write an announcement, check who it reaches, send a test, send it', async ({
  page,
  context,
}) => {
  const platform = await fakeAnnouncements(context)

  await page.goto('/admin/announcements')
  await expect(page.getByRole('heading', { level: 1, name: 'Announcements' })).toBeVisible()
  await expect(page.getByText('No announcements yet')).toBeVisible()

  // 1. Write it with the real editor: bold, a list, and a link that is not allowed.
  await page.getByRole('button', { name: 'Write an announcement' }).click()
  const sheet = page.getByRole('dialog', { name: 'Write an announcement' })
  await sheet.getByLabel(/^Title/).fill('AGM on Sunday')
  const editor = sheet.getByRole('textbox', { name: /^Message/ })
  await editor.click()
  await sheet.getByRole('button', { name: 'Bold' }).click()
  await page.keyboard.type('Please come')
  await sheet.getByRole('button', { name: 'Bold' }).click()
  await page.keyboard.type(' to the AGM.')
  await page.keyboard.press('Enter')
  await sheet.getByRole('button', { name: 'Bulleted list' }).click()
  await page.keyboard.type('Bring your receipt')

  await sheet.getByRole('button', { name: 'Link' }).click()
  await sheet.getByLabel('Link address').fill('javascript:alert(1)')
  await sheet.getByRole('button', { name: 'Apply' }).click()
  await expect(sheet.getByRole('alert')).toContainText('https://')
  await sheet.getByRole('button', { name: 'Cancel' }).first().click()
  await expect(sheet.getByLabel('Link address')).toHaveCount(0)

  // 2. Check and send: the message as members see it, and who it reaches.
  await sheet.getByRole('button', { name: 'Check and send' }).click()
  const check = page.getByRole('dialog', { name: 'Check and send' })
  await expect(check.getByLabel('Who it reaches')).toContainText('40')
  await expect(check.getByLabel('Who it reaches')).toContainText('34')
  await expect(check.getByText('120 of 500')).toBeVisible()
  const frame = check.frameLocator('iframe[title="How the announcement will look"]')
  await expect(frame.locator('strong')).toHaveText('Please come')
  await expect(frame.locator('li')).toHaveText('Bring your receipt')
  expect(platform.items[0]?.bodyHtml).not.toContain('javascript')

  // 3. A test to yourself first, then the real thing (which asks first).
  await check.getByRole('button', { name: 'Send a test to me' }).click()
  await expect.poll(() => platform.tests.length).toBe(1)
  await check.getByRole('button', { name: 'Send now' }).click()
  const confirm = page.getByRole('alertdialog')
  await expect(confirm).toContainText('34 of 40 members')
  await confirm.getByRole('button', { name: 'Send now' }).click()

  // 4. It is in the history, sent, with its delivery.
  await expect(page.getByRole('button', { name: 'AGM on Sunday', exact: true })).toBeVisible()
  await expect(page.getByText('34 emailed of 40')).toBeVisible()
  expect(platform.items[0]?.status).toBe('SENT')

  await page.getByRole('button', { name: 'AGM on Sunday', exact: true }).click()
  const detail = page.getByRole('dialog', { name: 'AGM on Sunday' })
  await expect(detail.getByText('4 have no email address')).toBeVisible()
})
