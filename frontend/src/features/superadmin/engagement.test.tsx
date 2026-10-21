import { cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { expectNoA11yViolations } from '@/test/a11y'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import { emptySuperadminApi } from '@/test/superadminApi'
import type {
  AdminAuditEntry,
  InviteRow,
  LeadView,
  PageResponse,
  PlatformAnnouncementView,
} from '@/types'

configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 20_000 })

const ok =
  (data: unknown, status = 200): MockHandler =>
  () => ({ status, data })
const body = (config: InternalAxiosRequestConfig) =>
  JSON.parse(config.data as string) as Record<string, unknown>
const query = (config: InternalAxiosRequestConfig) => (config.params as URLSearchParams).toString()
const page = <T,>(items: T[]): PageResponse<T> => ({
  items,
  total: items.length,
  page: 0,
  size: 20,
})

let mock: ReturnType<typeof installMockApi> | undefined
function api(handlers: Record<string, MockHandler>) {
  mock = installMockApi({ ...emptySuperadminApi(), ...handlers })
  return mock
}
beforeEach(() => {
  setSession('SUPER_ADMIN')
})
afterEach(() => {
  cleanup()
  mock?.restore()
  mock = undefined
  resetSession()
})

// ---- invites ----------------------------------------------------------------------------------

const invite = (over: Partial<InviteRow> = {}): InviteRow => ({
  userId: 'u-1',
  email: 'asha@example.test',
  fullName: 'Asha Rao',
  communityId: 'com-1',
  communityName: 'Lotus Residents',
  status: 'PENDING',
  invitedAt: '2026-10-16T08:00:00Z',
  expiresAt: '2026-10-23T08:00:00Z',
  acceptedAt: null,
  ...over,
})

describe('Invites', () => {
  it('shows each invitation with its status and the community, and passes axe', async () => {
    api({
      'GET /admin/invites': ok(
        page([
          invite(),
          invite({ userId: 'u-2', email: 'b@example.test', fullName: 'Ben', status: 'EXPIRED' }),
          invite({
            userId: 'u-3',
            email: 'c@example.test',
            fullName: 'Cy',
            status: 'ACCEPTED',
            acceptedAt: '2026-10-17T08:00:00Z',
          }),
          invite({ userId: 'u-4', email: 'd@example.test', fullName: 'Di', status: 'REVOKED' }),
        ]),
      ),
    })
    const { container } = renderApp('/superadmin/invites')

    expect((await screen.findAllByText('asha@example.test')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('Waiting').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Expired').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Accepted').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Revoked').length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: 'Lotus Residents' })[0]).toHaveAttribute(
      'href',
      '/superadmin/communities/com-1',
    )
    await expectNoA11yViolations(container)
  })

  it('sends the filters from the address, and has empty and error states', async () => {
    const seen: string[] = []
    api({
      'GET /admin/invites': (config) => {
        seen.push(query(config))
        return { status: 200, data: page([]) }
      },
    })
    renderApp('/superadmin/invites?status=EXPIRED&q=asha')
    expect(await screen.findByText('No invitations match')).toBeInTheDocument()
    expect(seen.at(-1)).toContain('status=EXPIRED')
    expect(seen.at(-1)).toContain('q=asha')
    expect(seen.at(-1)).toContain('sort=invitedAt%2Cdesc')
    cleanup()
    mock?.restore()

    api({ 'GET /admin/invites': () => problem(500, 'INTERNAL_ERROR') })
    renderApp('/superadmin/invites')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('offers resend and revoke only where they make sense, and asks before each', async () => {
    const calls: string[] = []
    api({
      'GET /admin/invites': ok(
        page([
          invite(),
          invite({ userId: 'u-3', email: 'c@example.test', fullName: 'Cy', status: 'ACCEPTED' }),
        ]),
      ),
      'POST /admin/invites/u-1/resend': () => {
        calls.push('resend')
        return { status: 202 }
      },
      'POST /admin/invites/u-1/revoke': () => {
        calls.push('revoke')
        return { status: 204 }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/invites')

    // An accepted invitation offers nothing to do.
    await screen.findAllByText('c@example.test')
    await user.click(screen.getAllByRole('button', { name: 'Actions for c@example.test' })[0]!)
    expect(await screen.findByRole('menuitem', { name: 'Already accepted' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    await user.keyboard('{Escape}')

    await user.click(
      (await screen.findAllByRole('button', { name: 'Actions for asha@example.test' }))[0]!,
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Send again' }))
    const resend = await screen.findByRole('alertdialog')
    expect(calls).toEqual([])
    await user.click(within(resend).getByRole('button', { name: 'Send' }))
    await waitFor(() => {
      expect(calls).toEqual(['resend'])
    })

    await user.click(
      (await screen.findAllByRole('button', { name: 'Actions for asha@example.test' }))[0]!,
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Revoke' }))
    await user.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Revoke' }),
    )
    await waitFor(() => {
      expect(calls).toEqual(['resend', 'revoke'])
    })
  })
})

// ---- leads ------------------------------------------------------------------------------------

const lead = (over: Partial<LeadView> = {}): LeadView => ({
  id: 'lead-1',
  name: 'Meera Nair',
  email: 'meera@example.test',
  phone: '+91 90000 00000',
  communityName: 'Green Park Society',
  size: 80,
  message: 'We would like a demo',
  status: 'NEW',
  handledBy: null,
  convertedCommunityId: null,
  source: 'website',
  createdAt: '2026-10-16T08:00:00Z',
  updatedAt: '2026-10-16T08:00:00Z',
  ...over,
})

describe('Leads', () => {
  const byStatus =
    (rows: Partial<Record<string, LeadView[]>>): MockHandler =>
    (config) => {
      const status = new URLSearchParams(query(config)).get('status') ?? ''
      return { status: 200, data: page(rows[status] ?? []) }
    }

  it('is a board with one column per stage, each with its own count', async () => {
    api({
      'GET /admin/leads': byStatus({
        NEW: [lead()],
        CONTACTED: [lead({ id: 'lead-2', name: 'Ravi', status: 'CONTACTED' })],
      }),
    })
    const { container } = renderApp('/superadmin/leads')

    const newColumn = await screen.findByRole('region', { name: 'New leads' })
    expect(await within(newColumn).findByText('Meera Nair')).toBeInTheDocument()
    expect(
      await within(screen.getByRole('region', { name: 'Contacted leads' })).findByText('Ravi'),
    ).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: 'Lost leads' })).getByText('No leads here.'),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('opens a lead, and moves it along the pipeline at once', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /admin/leads': byStatus({ NEW: [lead()] }),
      'GET /admin/leads/lead-1': ok(lead()),
      'PATCH /admin/leads/lead-1/status': (config) => {
        sent = body(config)
        return { status: 200, data: lead({ status: 'CONTACTED' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/leads')

    await user.click(await screen.findByRole('button', { name: /Meera Nair/ }))
    const sheet = await screen.findByRole('dialog', { name: 'Meera Nair' })
    expect(within(sheet).getByText('We would like a demo')).toBeInTheDocument()
    expect(within(sheet).getByRole('link', { name: 'Convert to a community' })).toHaveAttribute(
      'href',
      '/superadmin/communities?lead=lead-1',
    )
    await user.click(within(sheet).getByRole('combobox', { name: 'Status' }))
    await user.click(await screen.findByRole('option', { name: 'Contacted' }))
    await waitFor(() => {
      expect(sent).toEqual({ status: 'CONTACTED' })
    })
  })

  it('puts the old status back, and says why, when the change is refused', async () => {
    api({
      'GET /admin/leads': byStatus({ NEW: [lead()] }),
      'GET /admin/leads/lead-1': ok(lead()),
      'PATCH /admin/leads/lead-1/status': () => problem(409, 'INVALID_STATE_TRANSITION'),
    })
    const user = userEvent.setup()
    renderApp('/superadmin/leads?lead=lead-1')

    const sheet = await screen.findByRole('dialog', { name: 'Meera Nair' })
    const status = await within(sheet).findByRole('combobox', { name: 'Status' })
    expect(status).toHaveTextContent('New')
    await user.click(status)
    await user.click(await screen.findByRole('option', { name: 'Lost' }))
    await waitFor(() => {
      expect(within(sheet).getByRole('combobox', { name: 'Status' })).toHaveTextContent('New')
    })
  })

  it('shows a converted lead with a link to its community and no convert button', async () => {
    api({
      'GET /admin/leads': byStatus({}),
      'GET /admin/leads/lead-1': ok(lead({ status: 'CONVERTED', convertedCommunityId: 'com-7' })),
    })
    renderApp('/superadmin/leads?lead=lead-1')

    const sheet = await screen.findByRole('dialog', { name: 'Meera Nair' })
    expect(await within(sheet).findByRole('link', { name: 'Open the community' })).toHaveAttribute(
      'href',
      '/superadmin/communities/com-7',
    )
    expect(
      within(sheet).queryByRole('link', { name: 'Convert to a community' }),
    ).not.toBeInTheDocument()
  })

  it('has a table view with search and status filters sent to the API', async () => {
    const seen: string[] = []
    api({
      'GET /admin/leads': (config) => {
        seen.push(query(config))
        return { status: 200, data: page([]) }
      },
    })
    renderApp('/superadmin/leads?view=table&status=LOST&q=meera')
    expect(await screen.findByText('No leads match')).toBeInTheDocument()
    expect(seen.at(-1)).toContain('status=LOST')
    expect(seen.at(-1)).toContain('q=meera')
  })
})

// ---- announcements ----------------------------------------------------------------------------

const announcement = (over: Partial<PlatformAnnouncementView> = {}): PlatformAnnouncementView => ({
  id: 'ann-1',
  title: 'Planned maintenance',
  bodyHtml: '<p>We will be down</p>',
  bodyText: 'We will be down on Sunday.',
  kind: 'MAINTENANCE',
  communityIds: null, // the server sends null for "every community"
  sendEmail: true,
  banner: false,
  expiresAt: null,
  status: 'DRAFT',
  scheduledAt: null,
  sentAt: null,
  delivery: null,
  readCount: 0,
  createdBy: 'u-0',
  createdAt: '2026-10-16T08:00:00Z',
  updatedAt: '2026-10-16T08:00:00Z',
  ...over,
})

describe('Announcements', () => {
  it('lists the history with status, audience and channels, and passes axe', async () => {
    api({
      'GET /admin/announcements': ok(
        page([
          announcement(),
          announcement({
            id: 'ann-2',
            title: 'Diwali offer',
            status: 'SENT',
            kind: 'OFFER',
            banner: true,
            communityIds: ['a', 'b'],
            sentAt: '2026-10-10T08:00:00Z',
          }),
        ]),
      ),
    })
    const { container } = renderApp('/superadmin/announcements')

    expect((await screen.findAllByText('Planned maintenance')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('All communities').length).toBeGreaterThan(0)
    expect(screen.getAllByText('2 chosen').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Email and Banner').length).toBeGreaterThan(0)
    await expectNoA11yViolations(container)
  })

  it('has an empty state that offers the composer', async () => {
    api({ 'GET /admin/announcements': ok(page([])) })
    renderApp('/superadmin/announcements')
    expect(await screen.findByText('No announcements yet')).toBeInTheDocument()
  })

  it('checks the form: a message, a channel, and a future time', async () => {
    const post = vi.fn(() => ({ status: 201, data: announcement() }))
    api({ 'GET /admin/announcements': ok(page([])), 'POST /admin/announcements': post })
    const user = userEvent.setup()
    renderApp('/superadmin/announcements')

    await user.click((await screen.findAllByRole('button', { name: 'New announcement' }))[0]!)
    const sheet = await screen.findByRole('dialog', { name: 'New announcement' })
    await user.click(within(sheet).getByRole('switch', { name: 'Email each administrator' }))
    await user.click(within(sheet).getByRole('radio', { name: /Send at a time I choose/ }))
    await user.click(within(sheet).getByRole('button', { name: 'Schedule' }))

    expect(await within(sheet).findByText('Enter a title')).toBeInTheDocument()
    expect(within(sheet).getByText('Write the message')).toBeInTheDocument()
    expect(
      within(sheet).getByText('Choose how it reaches people: email, a banner in the app, or both'),
    ).toBeInTheDocument()
    expect(within(sheet).getByText('Choose a day')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('saves a draft to every community, by email', async () => {
    let created: Record<string, unknown> | undefined
    api({
      'GET /admin/announcements': ok(page([])),
      'POST /admin/announcements': (config) => {
        created = body(config)
        return { status: 201, data: announcement() }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/announcements')

    await user.click((await screen.findAllByRole('button', { name: 'New announcement' }))[0]!)
    const sheet = await screen.findByRole('dialog', { name: 'New announcement' })
    await user.type(within(sheet).getByLabelText(/^Title/), 'Planned maintenance')
    await user.type(within(sheet).getByLabelText(/^Message/), 'We will be down on Sunday.')
    await user.click(within(sheet).getByRole('button', { name: 'Save draft' }))
    await waitFor(() => {
      expect(created).toBeDefined()
    })
    expect(created).toEqual({
      title: 'Planned maintenance',
      body: 'We will be down on Sunday.',
      kind: 'ANNOUNCEMENT',
      sendEmail: true,
      banner: false,
    })
  })

  it('sends now only after a confirmation, and never creates a second copy on retry', async () => {
    const calls: string[] = []
    let attempts = 0
    api({
      'GET /admin/announcements': ok(page([])),
      'POST /admin/announcements': () => {
        calls.push('create')
        return { status: 201, data: announcement() }
      },
      'POST /admin/announcements/ann-1/send': () => {
        calls.push('send')
        attempts += 1
        return attempts === 1
          ? problem(500, 'INTERNAL_ERROR')
          : { status: 200, data: announcement({ status: 'SENT' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/announcements')

    await user.click((await screen.findAllByRole('button', { name: 'New announcement' }))[0]!)
    const sheet = await screen.findByRole('dialog', { name: 'New announcement' })
    await user.type(within(sheet).getByLabelText(/^Title/), 'Hello')
    await user.type(within(sheet).getByLabelText(/^Message/), 'World')
    await user.click(within(sheet).getByRole('radio', { name: /Send it now/ }))
    await user.click(within(sheet).getByRole('button', { name: 'Send now' }))

    const confirm = await screen.findByRole('alertdialog')
    expect(calls).toEqual([]) // nothing yet
    await user.click(within(confirm).getByRole('button', { name: 'Send now' }))
    await waitFor(() => {
      expect(calls).toEqual(['create', 'send'])
    })

    // The send failed: trying again sends the saved announcement, it does not create another.
    const again = await screen.findByRole('alertdialog')
    await user.click(within(again).getByRole('button', { name: 'Send now' }))
    await waitFor(() => {
      expect(calls).toEqual(['create', 'send', 'send'])
    })
  })

  it('previews who it would reach, sends a test to me, and shows delivery for a sent one', async () => {
    const calls: string[] = []
    api({
      'GET /admin/announcements': ok(
        page([
          announcement(),
          announcement({
            id: 'ann-2',
            title: 'Old news',
            status: 'SENT',
            sentAt: '2026-10-10T08:00:00Z',
            delivery: {
              recipientsTotal: 9,
              emailsQueued: 8,
              skippedNoEmail: 1,
              skippedNoConsent: 0,
              skippedQuota: 0,
            },
            readCount: 4,
          }),
        ]),
      ),
      'GET /admin/announcements/ann-1/preview': ok({
        title: 'Planned maintenance',
        bodyHtml: '',
        bodyText: '',
        kind: 'MAINTENANCE',
        communities: 12,
        adminRecipients: 15,
        sendEmail: true,
        banner: false,
      }),
      'POST /admin/announcements/ann-1/send-test': () => {
        calls.push('test')
        return { status: 200, data: { sentTo: 'root@example.test', queued: true } }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/announcements')

    await user.click((await screen.findAllByRole('button', { name: 'Planned maintenance' }))[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Planned maintenance' })
    expect(
      await within(dialog).findByText(/15 administrators in 12 communities/),
    ).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Send a test to me' }))
    await waitFor(() => {
      expect(calls).toEqual(['test'])
    })
    await user.keyboard('{Escape}')

    await user.click((await screen.findAllByRole('button', { name: 'Old news' }))[0]!)
    const sent = await screen.findByRole('dialog', { name: 'Old news' })
    expect(within(sent).getByText('What happened')).toBeInTheDocument()
    expect(within(sent).queryByRole('button', { name: 'Send now' })).not.toBeInTheDocument()
  })
})

// ---- audit ------------------------------------------------------------------------------------

const entry = (over: Partial<AdminAuditEntry> = {}): AdminAuditEntry => ({
  id: 'e-1',
  at: '2026-10-16T08:00:00Z',
  action: 'COMMUNITY_UPDATED',
  summary: 'The community was updated',
  actorId: 'u-0',
  actorName: 'Platform Admin',
  actorRole: 'SUPER_ADMIN',
  communityId: 'com-1',
  communityName: 'Lotus Residents',
  entityType: 'Community',
  entityId: 'com-1',
  before: { city: 'Pune', name: 'Lotus' },
  after: { city: 'Mumbai', name: 'Lotus', zip: '400001' },
  ip: '10.0.0.1',
  userAgent: null,
  requestId: 'req-1',
  ...over,
})

describe('Audit trail', () => {
  it('lists entries newest first, and expands one into a readable before and after', async () => {
    api({
      'GET /admin/audit': ok({ items: [entry()], nextCursor: null, hasMore: false }),
      'GET /admin/communities': ok(page([])),
    })
    const user = userEvent.setup()
    const { container } = renderApp('/superadmin/audit')

    expect(await screen.findByText('The community was updated')).toBeInTheDocument()
    expect(screen.getByText('That is everything that matches.')).toBeInTheDocument()
    await user.click(
      screen.getByRole('button', { name: /Show details for The community was updated/ }),
    )
    const diff = await screen.findByRole('table', { name: 'What changed' })
    expect(
      within(diff).getByRole('row', { name: /city Changed Pune → Mumbai/ }),
    ).toBeInTheDocument()
    expect(within(diff).getByRole('row', { name: /zip Added 400001/ })).toBeInTheDocument()
    expect(screen.getByText('1 field did not change.')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('sends the filters from the address and pages by cursor', async () => {
    const seen: string[] = []
    api({
      'GET /admin/audit': (config) => {
        const text = query(config)
        seen.push(text)
        return text.includes('cursor=c1')
          ? {
              status: 200,
              data: {
                items: [entry({ id: 'e-2', summary: 'Older entry' })],
                nextCursor: null,
                hasMore: false,
              },
            }
          : { status: 200, data: { items: [entry()], nextCursor: 'c1', hasMore: true } }
      },
      'GET /admin/communities': ok(page([])),
    })
    const user = userEvent.setup()
    renderApp(
      '/superadmin/audit?communityId=11111111-1111-4111-8111-111111111111&action=COMMUNITY_UPDATED&from=2026-10-01&to=2026-10-31',
    )

    expect(await screen.findByText('The community was updated')).toBeInTheDocument()
    expect(seen[0]).toContain('communityId=11111111-1111-4111-8111-111111111111')
    expect(seen[0]).toContain('action=COMMUNITY_UPDATED')
    expect(seen[0]).toContain('from=2026-10-01')
    expect(seen[0]).toContain('to=2026-10-31')
    expect(seen[0]).toContain('limit=50')

    await user.click(screen.getByRole('button', { name: 'Load older entries' }))
    expect(await screen.findByText('Older entry')).toBeInTheDocument()
    expect(seen.at(-1)).toContain('cursor=c1')
    expect(screen.getByText('The community was updated')).toBeInTheDocument() // the first page stays
  })

  it('has empty and error states, and refuses a user id that is not one', async () => {
    api({
      'GET /admin/audit': ok({ items: [], nextCursor: null, hasMore: false }),
      'GET /admin/communities': ok(page([])),
    })
    renderApp('/superadmin/audit?actor=not-a-uuid')
    expect(await screen.findByText('That is not a valid user id.')).toBeInTheDocument()
    expect(await screen.findByText('Nothing matches those filters')).toBeInTheDocument()
    cleanup()
    mock?.restore()

    api({
      'GET /admin/audit': () => problem(500, 'INTERNAL_ERROR'),
      'GET /admin/communities': ok(page([])),
    })
    renderApp('/superadmin/audit')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('exports with the same filters', async () => {
    const seen: string[] = []
    const created = vi.fn(() => 'blob:audit')
    URL.createObjectURL = created
    URL.revokeObjectURL = vi.fn()
    api({
      'GET /admin/audit': ok({ items: [entry()], nextCursor: null, hasMore: false }),
      'GET /admin/communities': ok(page([])),
      'GET /admin/audit/export': (config) => {
        seen.push(query(config))
        return { status: 200, data: new Blob(['a,b']), headers: { 'x-export-rows': '1' } }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/audit?action=LOGIN_SUCCESS')

    await screen.findByText('The community was updated')
    await user.click(screen.getByRole('button', { name: 'Export CSV' }))
    await waitFor(() => {
      expect(seen).toHaveLength(1)
    })
    expect(seen[0]).toContain('action=LOGIN_SUCCESS')
    expect(seen[0]).not.toContain('limit')
    expect(created).toHaveBeenCalled()
  })
})
