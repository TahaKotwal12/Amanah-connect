import { expect, test, type BrowserContext, type Route } from '@playwright/test'

/**
 * Complaints in a real browser: log one for a member, add an internal note and a member update,
 * then resolve it and email the member. The API is a small stand-in that keeps the complaint and
 * its thread.
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

interface Complaint {
  id: string
  subject: string
  description: string
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
  priority: string
  memberId: string | null
  assignedTo: string | null
}
interface Comment {
  id: string
  body: string
  visibility: 'INTERNAL' | 'MEMBER'
}

async function fakeComplaints(context: BrowserContext) {
  const complaints: Complaint[] = []
  const comments: Comment[] = []
  const emailed: boolean[] = []
  const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, json: body })
  const page = <T>(items: T[]) => ({ items, total: items.length, page: 0, size: 20 })

  const view = (c: Complaint) => ({
    id: c.id,
    subject: c.subject,
    description: c.description,
    status: c.status,
    priority: c.priority,
    category: null,
    memberId: c.memberId,
    memberNo: c.memberId ? 'M-0001' : null,
    memberName: c.memberId ? 'Asha Rao' : null,
    assignedTo: c.assignedTo,
    assignedToName: c.assignedTo ? 'Community Admin' : null,
    createdAt: '2026-10-18T10:00:00Z',
    updatedAt: '2026-10-18T10:00:00Z',
    resolvedAt: c.status === 'RESOLVED' ? '2026-10-18T12:00:00Z' : null,
    closedAt: null,
    ageDays: 0,
    slaTargetDays: 7,
    slaBreached: false,
    commentCount: comments.length,
    emailOutcome: null,
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
        limits: {},
        features: {},
        usage: { members: 1, emailsThisMonth: 0 },
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
    if (key === 'GET /community/members')
      return json(
        route,
        page([
          { id: 'm1', memberNo: 'M-0001', fullName: 'Asha Rao', status: 'ACTIVE', email: null },
        ]),
      )
    if (key === 'GET /community/complaints/assignees')
      return json(route, [{ id: 'u1', fullName: 'Community Admin', email: 'admin@example.test' }])
    if (key === 'GET /community/complaints/counts')
      return json(route, {
        total: complaints.length,
        open: complaints.filter((c) => c.status === 'OPEN').length,
        inProgress: 0,
        resolved: complaints.filter((c) => c.status === 'RESOLVED').length,
        closed: 0,
        active: complaints.filter((c) => c.status === 'OPEN').length,
        slaBreached: 0,
        urgentActive: 0,
        unassignedActive: 0,
        assignedToMe: 0,
        byCategory: {},
        byPriority: {},
      })
    if (key === 'GET /community/complaints') {
      const wanted = url.searchParams.getAll('status')
      return json(
        route,
        page(complaints.filter((c) => wanted.length === 0 || wanted.includes(c.status)).map(view)),
      )
    }
    if (key === 'POST /community/complaints') {
      const made: Complaint = {
        id: `cmp-${complaints.length + 1}`,
        subject: String(body.subject),
        description: String(body.description),
        status: 'OPEN',
        priority: body.priority ? String(body.priority) : 'MEDIUM',
        memberId: body.memberId ? String(body.memberId) : null,
        assignedTo: null,
      }
      complaints.push(made)
      return json(route, view(made), 201)
    }
    const one = /^(GET|POST) \/community\/complaints\/(cmp-\d+)(\/comments|\/status)?$/.exec(key)
    if (one) {
      const found = complaints.find((c) => c.id === one[2])
      if (!found) return json(route, { status: 404, code: 'NOT_FOUND', detail: 'none' }, 404)
      if (!one[3]) return json(route, view(found))
      if (one[3] === '/comments' && one[1] === 'GET')
        return json(
          route,
          comments.map((c) => ({
            id: c.id,
            authorId: 'u1',
            authorName: 'Community Admin',
            body: c.body,
            visibility: c.visibility,
            createdAt: '2026-10-18T11:00:00Z',
            emailOutcome: null,
          })),
        )
      if (one[3] === '/comments') {
        const made: Comment = {
          id: `k-${comments.length + 1}`,
          body: String(body.body),
          visibility: body.visibility as Comment['visibility'],
        }
        comments.push(made)
        return json(
          route,
          {
            ...made,
            authorId: 'u1',
            authorName: 'Community Admin',
            createdAt: '2026-10-18T11:00:00Z',
            emailOutcome: null,
          },
          201,
        )
      }
      found.status = body.status as Complaint['status']
      if (body.notifyMember === true) emailed.push(true)
      if (body.note)
        comments.push({
          id: `k-${comments.length + 1}`,
          body: String(body.note),
          visibility: body.notifyMember ? 'MEMBER' : 'INTERNAL',
        })
      return json(route, { ...view(found), emailOutcome: body.notifyMember ? 'QUEUED' : null })
    }
    return json(route, page([]))
  })
  return { complaints, comments, emailed }
}

test('log a complaint, add a note and an update, then resolve it', async ({ page, context }) => {
  const platform = await fakeComplaints(context)

  // 1. Log it on behalf of a member.
  await page.goto('/admin/complaints')
  await expect(page.getByRole('heading', { level: 1, name: 'Complaints' })).toBeVisible()
  await expect(page.getByText('No complaints yet')).toBeVisible()
  await page.getByRole('button', { name: 'Log a complaint' }).click()
  const sheet = page.getByRole('dialog', { name: 'Log a complaint' })
  await sheet.getByLabel(/^Subject/).fill('Water leak in B block')
  await sheet.getByLabel(/^What happened/).fill('The ceiling is dripping.')
  await sheet.getByRole('combobox', { name: /On behalf of/ }).click()
  await page.getByRole('option', { name: /Asha Rao/ }).click()
  await sheet.getByRole('button', { name: 'Log complaint' }).click()

  // 2. It opens in the drawer. Add an internal note, then an update the member can see.
  const drawer = page.getByRole('dialog', { name: 'Water leak in B block' })
  await expect(drawer).toBeVisible()
  await drawer.getByRole('textbox', { name: 'Internal note' }).fill('Called the plumber.')
  await drawer.getByRole('button', { name: 'Add note' }).click()
  await expect(drawer.getByRole('list', { name: 'Notes and updates' })).toContainText(
    'Called the plumber.',
  )
  await expect(drawer.getByText('Internal note').first()).toBeVisible()

  await drawer.getByRole('radio', { name: /Update the member can see/ }).check()
  await drawer.getByRole('textbox', { name: 'Update for the member' }).fill('The plumber comes tomorrow.')
  await drawer.getByRole('button', { name: 'Add update' }).click()
  await expect(drawer.getByText('Visible to member')).toBeVisible()
  expect(platform.comments.map((c) => c.visibility)).toEqual(['INTERNAL', 'MEMBER'])

  // 3. Resolve it and email the member.
  await drawer.getByRole('button', { name: 'Change status' }).click()
  const dialog = page.getByRole('dialog', { name: 'Change status' })
  await dialog.getByRole('radio', { name: 'Resolved' }).check()
  await dialog.getByLabel('Note (optional)').fill('Leak fixed.')
  await dialog.getByRole('checkbox', { name: /Notify the member/ }).click()
  await dialog.getByRole('button', { name: 'Save status' }).click()
  await expect(drawer.getByText('Resolved').first()).toBeVisible()
  expect(platform.complaints[0]?.status).toBe('RESOLVED')
  expect(platform.emailed).toHaveLength(1)

  // 4. It is now under Resolved in the list.
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Resolved', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Water leak in B block', exact: true })).toBeVisible()
})
