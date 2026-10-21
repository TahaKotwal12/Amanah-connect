import { expect, test, type BrowserContext, type Route } from '@playwright/test'

/**
 * Adding a member, and the whole invite journey (create a link, register on the public page,
 * approve), in a real browser. The API is a small stand-in that keeps the lists that matter.
 */
const ME = {
  user: {
    id: '3f2b8c1e-6d7a-4c58-9a55-2d1a0e8c9b11',
    email: 'admin@example.test',
    fullName: 'Community Admin',
    role: 'COMMUNITY_ADMIN',
    status: 'ACTIVE',
  },
  community: { id: 'c1', name: 'Lotus Residents', slug: 'lotus', status: 'ACTIVE' },
  flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: false },
}

interface Member {
  id: string
  memberNo: string
  fullName: string
  email: string | null
  status: 'ACTIVE'
}
interface Registration {
  id: string
  fullName: string
  email: string
  consentEmail: boolean
  status: 'PENDING' | 'APPROVED'
  memberNo: string | null
}

async function fakeCommunity(context: BrowserContext) {
  const members: Member[] = []
  const registrations: Registration[] = []
  const invites: Array<{ id: string; token: string }> = []
  const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, json: body })
  const page = <T>(items: T[]) => ({ items, total: items.length, page: 0, size: 20 })
  const asMemberView = (m: Member) => ({
    ...m,
    phone: null,
    group: null,
    statusReason: null,
    statusChangedAt: null,
    joinedOn: null,
    customFields: null,
    consentEmail: true,
    createdAt: '2026-10-18T10:00:00Z',
    updatedAt: '2026-10-18T10:00:00Z',
  })
  const asRegistrationView = (r: Registration) => ({
    ...r,
    phone: null,
    group: null,
    createdAt: '2026-10-18T10:00:00Z',
    reviewedAt: null,
    rejectionReason: null,
    memberId: null,
    emailUsedByMemberNo: null,
  })
  const asInviteView = (id: string) => ({
    id,
    state: 'ACTIVE',
    expiresAt: '2026-11-01T00:00:00Z',
    maxUses: 100,
    usedCount: registrations.length,
    defaultGroup: null,
    invitedEmail: null,
    revokedAt: null,
    createdAt: '2026-10-18T10:00:00Z',
  })

  await context.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/v1', '')
    const key = `${request.method()} ${path}`
    const body = (request.postDataJSON() ?? {}) as Record<string, string | boolean>

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
        limits: { max_members: 50 },
        features: {},
        usage: { members: members.length, emailsThisMonth: 0 },
        communityStatus: 'ACTIVE',
        subscription: null,
        expiringWindowDays: 30,
      })
    if (key === 'GET /community/notifications/summary')
      return json(route, { unread: 0, banners: [] })
    if (key === 'GET /community/notifications') return json(route, page([]))
    if (key === 'GET /community/settings')
      return json(route, {
        name: 'Lotus Residents',
        logoUrl: null,
        currency: 'INR',
        memberGroupLabel: 'Group',
      })
    if (key === 'GET /community/members/counts')
      return json(route, {
        total: members.length,
        active: members.length,
        inactive: 0,
        newThisMonth: members.length,
        pendingRegistrations: registrations.filter((r) => r.status === 'PENDING').length,
      })
    if (key === 'GET /community/members') return json(route, page(members.map(asMemberView)))
    if (key === 'POST /community/members') {
      const created: Member = {
        id: `m-${members.length + 1}`,
        memberNo: `M-${String(members.length + 1).padStart(4, '0')}`,
        fullName: String(body.fullName),
        email: body.email ? String(body.email) : null,
        status: 'ACTIVE',
      }
      members.push(created)
      return json(route, asMemberView(created), 201)
    }
    if (key === 'GET /community/member-invites')
      return json(route, page(invites.map((i) => asInviteView(i.id))))
    if (key === 'POST /community/member-invites') {
      const token = `tok${invites.length + 1}abc`
      const id = `i-${invites.length + 1}`
      invites.push({ id, token })
      return json(
        route,
        {
          invite: asInviteView(id),
          link: `https://app.example.test/join/${token}`,
          qrCodePngBase64: 'iVBORw0KGgo=',
        },
        201,
      )
    }
    const publicInvite = /^(GET|POST) \/public\/invites\/([^/]+)(\/register)?$/.exec(key)
    if (publicInvite) {
      if (!invites.some((i) => i.token === publicInvite[2]))
        return json(route, { status: 404, code: 'INVITE_UNAVAILABLE', detail: 'unavailable' }, 404)
      if (publicInvite[1] === 'GET')
        return json(route, {
          communityName: 'Lotus Residents',
          logoUrl: null,
          groupLabel: 'Group',
          defaultGroup: null,
          fields: [
            { name: 'fullName', label: 'Full name', type: 'text', required: true, maxLength: 150 },
            { name: 'email', label: 'Email', type: 'email', required: true, maxLength: 254 },
          ],
        })
      registrations.push({
        id: `r-${registrations.length + 1}`,
        fullName: String(body.fullName),
        email: String(body.email),
        consentEmail: body.consentEmail === true,
        status: 'PENDING',
        memberNo: null,
      })
      return json(route, { received: true })
    }
    if (key === 'GET /community/registrations') {
      const status = new URL(request.url()).searchParams.get('status')
      const rows = registrations.filter((r) => !status || r.status === status)
      return json(route, page(rows.map(asRegistrationView)))
    }
    const approve = /^POST \/community\/registrations\/(r-\d+)\/approve$/.exec(key)
    if (approve) {
      const found = registrations.find((r) => r.id === approve[1])
      if (!found) return json(route, { status: 404, code: 'NOT_FOUND', detail: 'none' }, 404)
      found.status = 'APPROVED'
      const member: Member = {
        id: `m-${members.length + 1}`,
        memberNo: `M-${String(members.length + 1).padStart(4, '0')}`,
        fullName: found.fullName,
        email: found.email,
        status: 'ACTIVE',
      }
      members.push(member)
      found.memberNo = member.memberNo
      return json(route, asRegistrationView(found))
    }
    return json(route, page([]))
  })
  return { members, registrations, invites }
}

test('add a member and see them in the list', async ({ page, context }) => {
  const platform = await fakeCommunity(context)
  await page.goto('/admin/members')
  await expect(page.getByRole('heading', { level: 1, name: 'Members' })).toBeVisible()
  await expect(page.getByText('No members yet')).toBeVisible()
  await expect(page.getByText('0 of 50')).toBeVisible()

  await page.getByRole('button', { name: 'Add member' }).first().click()
  const sheet = page.getByRole('dialog', { name: 'Add a member' })
  await sheet.getByLabel(/^Full name/).fill('Asha Rao')
  await sheet.getByLabel('Email', { exact: true }).fill('asha@example.test')
  await sheet.getByRole('button', { name: 'Add member' }).click()

  await expect(page.getByRole('link', { name: 'Asha Rao' }).first()).toBeVisible()
  await expect(page.getByText('M-0001').first()).toBeVisible()
  await expect(page.getByText('1 of 50')).toBeVisible()
  expect(platform.members).toHaveLength(1)
})

test('create an invite link, register on the public page, then approve', async ({
  page,
  context,
}) => {
  const platform = await fakeCommunity(context)

  // 1. The admin makes a link and copies it.
  await page.goto('/admin/invites')
  await page.getByRole('button', { name: 'Create invite link' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Create an invite link' })
  await dialog.getByRole('button', { name: 'Create link' }).click()
  const link = await page
    .getByRole('dialog', { name: 'Your invite link' })
    .getByLabel('Invite link')
    .inputValue()
  expect(link).toContain('/join/')
  const token = link.split('/join/')[1] ?? ''
  await page
    .getByRole('dialog', { name: 'Your invite link' })
    .getByRole('button', { name: 'Done' })
    .click()

  // 2. Someone opens it on their phone and registers. No sign-in.
  const visitor = await context.newPage()
  await visitor.goto(`/join/${token}`)
  await expect(
    visitor.getByRole('heading', { level: 1, name: 'Join Lotus Residents' }),
  ).toBeVisible()
  await visitor.getByLabel(/^Full name/).fill('Priya Shah')
  await visitor.getByLabel(/^Email/).fill('priya@example.test')
  await visitor.getByRole('checkbox', { name: /may email me/ }).check()
  await visitor.getByRole('button', { name: 'Send my registration' }).click()
  await expect(
    visitor.getByRole('heading', { level: 1, name: 'Thank you, your registration was received' }),
  ).toBeVisible()
  expect(platform.registrations).toHaveLength(1)
  expect(platform.registrations[0]?.consentEmail).toBe(true)

  // A wrong link says so, plainly.
  await visitor.goto('/join/not-a-real-link')
  await expect(
    visitor.getByRole('heading', { level: 1, name: 'This invitation cannot be used' }),
  ).toBeVisible()
  await visitor.close()

  // 3. The admin sees the registration waiting, approves it, and the member exists.
  await page.goto('/admin/invites?tab=registrations')
  await expect(page.getByText('Priya Shah')).toBeVisible()
  await page.getByRole('button', { name: 'Approve Priya Shah' }).click()
  await expect(page.getByText('Nobody is waiting')).toBeVisible()
  expect(platform.members.map((member) => member.fullName)).toEqual(['Priya Shah'])

  await page.goto('/admin/members')
  await expect(page.getByRole('link', { name: 'Priya Shah' }).first()).toBeVisible()
})
