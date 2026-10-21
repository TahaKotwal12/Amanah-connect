import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * Creating a community and suspending it, in a real browser. The API is a small stand-in that
 * keeps the one piece of state that matters here: the list of communities.
 */
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

const PLAN = {
  id: 'plan-1',
  code: 'STARTER',
  name: 'Starter',
  priceMonthly: '499.00',
  priceYearly: '4990.00',
  limits: {},
  features: {},
  publicPlan: true,
  active: true,
  sortOrder: 1,
  communityCount: 0,
  createdAt: '2026-01-01T00:00:00Z',
}

interface Stored {
  id: string
  name: string
  slug: string
  status: string
  statusReason: string | null
  ownerName: string
  ownerEmail: string
  version: number
}

async function fakePlatform(page: Page) {
  const communities: Stored[] = []
  const calls: string[] = []
  const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, json: body })

  const asListItem = (c: Stored) => ({
    id: c.id,
    name: c.name,
    slug: c.slug,
    status: c.status,
    plan: { id: PLAN.id, code: PLAN.code, name: PLAN.name },
    memberCount: 0,
    subscriptionEndsOn: null,
    subscriptionStatus: null,
    ownerName: c.ownerName,
    ownerEmail: c.ownerEmail,
    createdAt: '2026-10-17T10:00:00Z',
  })
  const asView = (c: Stored) => ({
    ...asListItem(c),
    statusReason: c.statusReason,
    statusChangedAt: null,
    contactName: null,
    contactEmail: null,
    contactPhone: null,
    addressLine1: null,
    addressLine2: null,
    city: null,
    state: null,
    postalCode: null,
    country: 'IN',
    dateOfEstablishment: null,
    currency: 'INR',
    financialYearStartMonth: 4,
    require2fa: false,
    owner: { id: 'u-1', fullName: c.ownerName, email: c.ownerEmail, status: 'INVITED' },
    version: c.version,
  })

  await page.route('**/api/v1/auth/refresh', (route) =>
    json(route, {
      accessToken: 'e2e',
      tokenType: 'Bearer',
      expiresIn: 900,
      mfaSetupRequired: false,
    }),
  )
  await page.route('**/api/v1/auth/me', (route) => json(route, ME))
  await page.route('**/api/v1/admin/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/v1/admin', '')
    const key = `${request.method()} ${path}`
    calls.push(key)
    const body = (request.postDataJSON() ?? {}) as Record<string, string>

    if (key === 'GET /plans') return json(route, [PLAN])
    if (key === 'GET /communities') {
      const items = communities.map(asListItem)
      return json(route, { items, total: items.length, page: 0, size: 20 })
    }
    if (key === 'POST /communities') {
      const created: Stored = {
        id: `com-${communities.length + 1}`,
        name: body.name ?? '',
        slug: (body.name ?? '').toLowerCase().replace(/\s+/g, '-'),
        status: 'PENDING',
        statusReason: null,
        ownerName: body.ownerName ?? '',
        ownerEmail: body.ownerEmail ?? '',
        version: 0,
      }
      communities.push(created)
      return json(route, asView(created), 201)
    }
    const detail = /^(GET|POST) \/communities\/(com-\d+)(?:\/(suspend|activate))?$/.exec(key)
    if (detail) {
      const found = communities.find((c) => c.id === detail[2])
      if (!found)
        return json(route, { status: 404, code: 'NOT_FOUND', detail: 'No such community' }, 404)
      if (detail[3] === 'suspend') {
        found.status = 'SUSPENDED'
        found.statusReason = body.reason ?? null
        found.version += 1
      }
      if (detail[3] === 'activate') {
        found.status = 'ACTIVE'
        found.statusReason = null
        found.version += 1
      }
      return json(route, asView(found))
    }
    return json(route, { items: [], total: 0, page: 0, size: 20 })
  })
  return { communities, calls }
}

test('create a community, see it in the list, then suspend it', async ({ page }) => {
  const platform = await fakePlatform(page)
  await page.goto('/superadmin/communities')
  await expect(page.getByRole('heading', { level: 1, name: 'Communities' })).toBeVisible()
  await expect(page.getByText('No communities yet')).toBeVisible()

  // Create: four steps.
  await page.getByRole('button', { name: 'New community' }).first().click()
  const wizard = page.getByRole('dialog', { name: 'New community' })
  await wizard.getByLabel(/^Community name/).fill('Lotus Residents')
  await wizard.getByRole('button', { name: 'Next' }).click()
  await wizard.getByRole('radio', { name: /Starter/ }).check()
  await wizard.getByRole('button', { name: 'Next' }).click()
  await wizard.getByLabel(/^Owner’s name/).fill('Asha Rao')
  await wizard.getByLabel(/^Owner’s email/).fill('asha@example.test')
  await wizard.getByRole('button', { name: 'Next' }).click()
  await expect(wizard.getByText('asha@example.test', { exact: false })).toBeVisible()
  await wizard.getByRole('button', { name: 'Create community' }).click()

  // It opens on the new community, which starts out waiting to start.
  await expect(page.getByRole('heading', { level: 1, name: 'Lotus Residents' })).toBeVisible()
  expect(platform.communities).toHaveLength(1)

  // Back in the list it appears.
  await page.getByRole('link', { name: 'Communities', exact: true }).first().click()
  await expect(page.getByRole('link', { name: 'Lotus Residents' }).first()).toBeVisible()

  // Activate then suspend from the row menu. Suspending needs a reason.
  await page.getByRole('button', { name: 'Actions for Lotus Residents' }).first().click()
  await page.getByRole('menuitem', { name: 'Activate' }).click()
  await page
    .getByRole('dialog', { name: 'Activate Lotus Residents?' })
    .getByRole('button', { name: 'Activate' })
    .click()
  await expect.poll(() => platform.communities[0]?.status).toBe('ACTIVE')

  await page.getByRole('button', { name: 'Actions for Lotus Residents' }).first().click()
  await page.getByRole('menuitem', { name: 'Suspend' }).click()
  const dialog = page.getByRole('dialog', { name: 'Suspend Lotus Residents?' })
  await expect(dialog.getByRole('button', { name: 'Suspend' })).toBeDisabled()
  await dialog.getByLabel(/^Reason/).fill('Unpaid for three months')
  await dialog.getByRole('button', { name: 'Suspend' }).click()

  await expect.poll(() => platform.communities[0]?.status).toBe('SUSPENDED')
  expect(platform.communities[0]?.statusReason).toBe('Unpaid for three months')
  await expect(page.getByText('Suspended').first()).toBeVisible()
})
