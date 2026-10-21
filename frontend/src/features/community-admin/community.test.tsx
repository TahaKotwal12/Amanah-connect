import { cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { createQueryClient } from '@/lib/queryClient'
import { usePlanLimitStore } from '@/store/planLimitStore'
import { expectNoA11yViolations } from '@/test/a11y'
import { emptyCommunityApi } from '@/test/communityApi'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import type {
  CommunityPlanView,
  Dashboard,
  ImportReport,
  InvoiceView,
  MemberView,
  PageResponse,
} from '@/types'

configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 25_000 })

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
function api(handlers: Record<string, MockHandler> = {}) {
  mock = installMockApi({ ...emptyCommunityApi(), ...handlers })
  return mock
}
/** The app's own error handling (so a plan limit reaches its dialog), with no retries. */
function render(entry: string) {
  const client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false }, mutations: { retry: false } })
  return renderApp(entry, { client })
}
beforeEach(() => {
  setSession('COMMUNITY_ADMIN')
})
afterEach(() => {
  cleanup()
  mock?.restore()
  mock = undefined
  usePlanLimitStore.getState().dismiss()
  resetSession()
})

// ---- fixtures ---------------------------------------------------------------------------------

const plan = (over: Partial<CommunityPlanView> = {}): CommunityPlanView => ({
  planCode: 'STARTER',
  planName: 'Starter',
  limits: { max_members: 50 },
  features: {},
  usage: { members: 10, emailsThisMonth: 3 },
  communityStatus: 'ACTIVE',
  subscription: {
    status: 'ACTIVE',
    periodStart: '2026-01-01',
    periodEnd: '2027-01-01',
    daysLeft: 200,
  },
  expiringWindowDays: 30,
  ...over,
})
const member = (over: Partial<MemberView> = {}): MemberView => ({
  id: 'm-1',
  memberNo: 'M-0001',
  fullName: 'Asha Rao',
  email: 'asha@example.test',
  phone: '+91 98765 43210',
  group: 'Block A',
  status: 'ACTIVE',
  statusReason: null,
  statusChangedAt: null,
  joinedOn: '2024-04-01',
  customFields: null,
  consentEmail: true,
  createdAt: '2024-04-01T10:00:00Z',
  updatedAt: '2024-04-01T10:00:00Z',
  ...over,
})
const invoice = (over: Partial<InvoiceView> = {}): InvoiceView => ({
  id: 'inv-1',
  invoiceNo: 'INV-0001',
  status: 'OVERDUE',
  kind: 'MAINTENANCE',
  memberId: 'm-1',
  memberNo: 'M-0001',
  memberName: 'Asha Rao',
  feePlanId: null,
  period: '2026-09',
  description: 'Maintenance September',
  amount: '2500.00',
  amountPaid: '500.00',
  balance: '2000.00',
  issuedOn: '2026-09-01',
  dueDate: '2026-09-15',
  cancelReason: null,
  cancelledAt: null,
  version: 1,
  createdAt: '2026-09-01T10:00:00Z',
  ...over,
})
const dashboard = (over: Partial<Dashboard> = {}): Dashboard => ({
  generatedAt: '2026-10-18T10:00:00Z',
  currency: 'INR',
  month: '2026-10',
  members: { total: 60, active: 48 },
  collection: {
    billed: '50000.00',
    collected: '30000.00',
    outstanding: '20000.00',
    receivedInMonth: '35000.50',
  },
  overdue: { count: 4, amount: '8000.00' },
  netBalance: '123456.00',
  complaints: { open: 3, slaBreached: 1 },
  unreadSupportMessages: 0,
  upcomingDues: {
    days: 7,
    count: 2,
    amount: '3000.00',
    items: [
      {
        invoiceId: 'inv-9',
        invoiceNo: 'INV-0009',
        memberId: 'm-9',
        memberName: 'Ravi Menon',
        dueDate: '2026-10-22',
        balance: '1500.00',
        status: 'ISSUED',
      },
    ],
  },
  recentActivity: [
    {
      at: '2026-10-17T08:00:00Z',
      summary: 'A payment was recorded',
      actor: 'Asha Rao',
      byPlatformStaff: false,
      action: 'PAYMENT_RECORDED',
    },
  ],
  collectionTrend: [
    { month: '2026-08', billed: '40000.00', collected: '39000.00' },
    { month: '2026-09', billed: '50000.00', collected: '45000.00' },
  ],
  ...over,
})

// ---- the shell: header, bell, banners ----------------------------------------------------------

describe('Community shell', () => {
  it('shows the community name and logo in the header', async () => {
    api({
      'GET /community/settings': ok({
        name: 'Lotus Residents',
        logoUrl: 'https://files.example.test/logo.png',
        currency: 'INR',
        memberGroupLabel: 'Block',
      }),
    })
    render('/admin/dashboard')
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' })
    await waitFor(() => {
      expect(document.querySelector('header img')).toHaveAttribute(
        'src',
        'https://files.example.test/logo.png',
      )
    })
    expect(document.querySelector('header img')).toHaveAttribute('alt', '') // the name is next to it
  })

  it('warns when the plan is about to end, in words and days', async () => {
    api({
      'GET /community/plan': ok(
        plan({
          subscription: {
            status: 'EXPIRING',
            periodStart: '2025-11-01',
            periodEnd: '2026-11-05',
            daysLeft: 18,
          },
        }),
      ),
    })
    render('/admin/dashboard')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Your plan ends in 18 days')
    expect(within(alert).getByRole('link', { name: 'Renew' })).toHaveAttribute(
      'href',
      '/admin/support',
    )
  })

  it('says so when the plan has ended', async () => {
    api({
      'GET /community/plan': ok(
        plan({
          subscription: {
            status: 'EXPIRED',
            periodStart: '2025-01-01',
            periodEnd: '2026-01-01',
            daysLeft: -290,
          },
        }),
      ),
    })
    render('/admin/dashboard')
    expect(await screen.findByRole('alert')).toHaveTextContent('Your plan ended on')
  })

  it('shows no banner when everything is fine', async () => {
    api({ 'GET /community/plan': ok(plan()) })
    render('/admin/dashboard')
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' })
    await new Promise((resolve) => setTimeout(resolve, 300))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('explains a suspension and switches the writing buttons off, with the reason beside them', async () => {
    api({
      'GET /community/plan': ok(plan({ communityStatus: 'SUSPENDED' })),
      'GET /community/dashboard': ok(dashboard()),
    })
    render('/admin/dashboard')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This community is suspended')
    expect(alert).toHaveTextContent('nothing can be added or changed')
    for (const label of ['Add member', 'Create invoice', 'Record payment', 'Add expense']) {
      expect(await screen.findByRole('button', { name: label })).toBeDisabled()
    }
    expect(
      screen.getByText(
        'This community is suspended, so nothing can be added or changed until it is activated.',
      ),
    ).toBeInTheDocument()
  })

  it('lets a platform announcement be read and dismissed as a banner', async () => {
    const calls: string[] = []
    api({
      'GET /community/notifications/summary': ok({
        unread: 1,
        banners: [
          {
            id: 'n-1',
            title: 'Planned maintenance',
            bodyHtml: '',
            bodyText: 'We are down on Sunday.',
            kind: 'MAINTENANCE',
            banner: true,
            sentAt: '2026-10-17T08:00:00Z',
            expiresAt: null,
            read: false,
          },
        ],
      }),
      'POST /community/notifications/n-1/read': () => {
        calls.push('read')
        return { status: 204 }
      },
    })
    const user = userEvent.setup()
    render('/admin/dashboard')

    expect(await screen.findByText('Planned maintenance')).toBeInTheDocument()
    expect(screen.getByText('We are down on Sunday.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    await waitFor(() => {
      expect(calls).toEqual(['read'])
    })
  })

  it('counts new registrations and announcements on the bell, and lists them', async () => {
    api({
      'GET /community/notifications/summary': ok({ unread: 2, banners: [] }),
      'GET /community/members/counts': ok({
        total: 5,
        active: 5,
        inactive: 0,
        newThisMonth: 1,
        pendingRegistrations: 3,
      }),
      'GET /community/notifications': ok(
        page([
          {
            id: 'n-1',
            title: 'Diwali offer',
            bodyHtml: '',
            bodyText: 'Save on renewal',
            kind: 'OFFER',
            banner: false,
            sentAt: '2026-10-16T08:00:00Z',
            expiresAt: null,
            read: false,
          },
        ]),
      ),
    })
    const user = userEvent.setup()
    render('/admin/dashboard')

    const bell = await screen.findByRole('button', { name: 'Notifications, 5 unread' })
    await user.click(bell)
    const link = await screen.findByRole('link', { name: '3 new registrations are waiting' })
    expect(link).toHaveAttribute('href', '/admin/invites?tab=registrations')
    expect(await screen.findByText('Diwali offer')).toBeInTheDocument()
  })
})

// ---- dashboard --------------------------------------------------------------------------------

describe('Community dashboard', () => {
  const full = () => ({
    'GET /community/dashboard': ok(dashboard()),
    'GET /community/invoices': ok(page([invoice()])),
  })

  it('shows the six numbers, the trend as a table, who is overdue, and what happened; passes axe', async () => {
    api(full())
    const { container } = render('/admin/dashboard')

    const numbers = await screen.findByRole('region', { name: 'Key numbers' })
    await waitFor(() => {
      expect(within(numbers).getByText('48')).toBeInTheDocument()
    })
    expect(within(numbers).getByText('₹35,000.50')).toBeInTheDocument() // collected this month
    expect(within(numbers).getByText('₹20,000.00')).toBeInTheDocument() // outstanding
    expect(within(numbers).getByText('₹8,000.00')).toBeInTheDocument() // overdue
    expect(within(numbers).getByText('4 invoices are past their due date')).toBeInTheDocument()
    expect(within(numbers).getByText('₹1,23,456.00')).toBeInTheDocument() // net balance
    expect(within(numbers).getByText('3')).toBeInTheDocument() // open complaints
    expect(within(numbers).getByText('1 past their response target')).toBeInTheDocument()

    const trend = await screen.findByRole('table', { name: 'Billed and collected by month' })
    expect(
      within(trend).getByRole('row', { name: /Sep 2026.*₹50,000\.00.*₹45,000\.00/ }),
    ).toBeInTheDocument()

    const overdue = await screen.findByRole('table', { name: 'Invoices past their due date' })
    expect(within(overdue).getByRole('link', { name: 'Asha Rao' })).toHaveAttribute(
      'href',
      '/admin/members/m-1',
    )
    expect(within(overdue).getByText('₹2,000.00')).toBeInTheDocument()

    expect(screen.getByText('A payment was recorded')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ravi Menon' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('has quick actions that lead to the right place', async () => {
    api(full())
    render('/admin/dashboard')
    const actions = await screen.findByRole('region', { name: 'Quick actions' })
    expect(within(actions).getByRole('link', { name: 'Add member' })).toHaveAttribute(
      'href',
      '/admin/members?new=1',
    )
    expect(within(actions).getByRole('link', { name: 'Create invoice' })).toHaveAttribute(
      'href',
      '/admin/billing?new=invoice',
    )
    expect(within(actions).getByRole('link', { name: 'Record payment' })).toHaveAttribute(
      'href',
      '/admin/billing?new=payment',
    )
    expect(within(actions).getByRole('link', { name: 'Add expense' })).toHaveAttribute(
      'href',
      '/admin/budget?new=expense',
    )
  })

  it('has an empty state for nobody overdue, and an error with a retry', async () => {
    api({ ...full(), 'GET /community/invoices': ok(page([])) })
    render('/admin/dashboard')
    expect(await screen.findByText('Nobody is overdue')).toBeInTheDocument()
    cleanup()
    mock?.restore()

    let attempts = 0
    api({
      'GET /community/dashboard': () => {
        attempts += 1
        return attempts === 1 ? problem(500, 'INTERNAL_ERROR') : { status: 200, data: dashboard() }
      },
    })
    const user = userEvent.setup()
    render('/admin/dashboard')
    await user.click(await screen.findByRole('button', { name: /try again|retry/i }))
    expect(await screen.findByText('48')).toBeInTheDocument()
  })
})

// ---- members ----------------------------------------------------------------------------------

describe('Members list', () => {
  it('lists members with their status and group, and passes axe', async () => {
    api({
      'GET /community/members': ok(
        page([
          member(),
          member({
            id: 'm-2',
            memberNo: 'M-0002',
            fullName: 'Ravi Menon',
            status: 'INACTIVE',
            group: null,
          }),
        ]),
      ),
    })
    const { container } = render('/admin/members')
    expect((await screen.findAllByRole('link', { name: 'Asha Rao' }))[0]).toHaveAttribute(
      'href',
      '/admin/members/m-1',
    )
    expect(screen.getAllByText('Inactive').length).toBeGreaterThan(0)
    await expectNoA11yViolations(container)
  })

  it('sends search, status and group from the address, by name by default', async () => {
    const seen: string[] = []
    api({
      'GET /community/members': (config) => {
        seen.push(query(config))
        return { status: 200, data: page([]) }
      },
    })
    render('/admin/members?q=asha&status=INACTIVE&group=Block+A')
    expect(await screen.findByText('No members match')).toBeInTheDocument()
    const last = seen.at(-1) ?? ''
    expect(last).toContain('q=asha')
    expect(last).toContain('status=INACTIVE')
    expect(last).toContain('group=Block+A')
    expect(last).toContain('sort=name%2Casc')
  })

  it('has an empty state with a way to add the first member, and an error with a retry', async () => {
    api({ 'GET /community/members': ok(page([])) })
    render('/admin/members')
    expect(await screen.findByText('No members yet')).toBeInTheDocument()
    cleanup()
    mock?.restore()

    api({ 'GET /community/members': () => problem(500, 'INTERNAL_ERROR') })
    render('/admin/members')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('uses the community’s own word for the group', async () => {
    api({
      'GET /community/settings': ok({
        name: 'Lotus',
        logoUrl: null,
        currency: 'INR',
        memberGroupLabel: 'Block',
      }),
      'GET /community/members': ok(page([member()])),
    })
    render('/admin/members')
    expect(await screen.findByRole('columnheader', { name: /^Block/ })).toBeInTheDocument()
  })
})

describe('Adding and changing members', () => {
  it('adds a member and shows them in the list, sending only what was filled in', async () => {
    const created: Array<Record<string, unknown>> = []
    const members: MemberView[] = []
    api({
      'GET /community/members': () => ({ status: 200, data: page([...members]) }),
      'POST /community/members': (config) => {
        created.push(body(config))
        const saved = member({
          id: 'm-5',
          memberNo: 'M-0005',
          fullName: 'Priya Shah',
          email: null,
          phone: null,
          group: null,
          joinedOn: null,
        })
        members.push(saved)
        return { status: 201, data: saved }
      },
    })
    const user = userEvent.setup()
    render('/admin/members?new=1')

    const sheet = await screen.findByRole('dialog', { name: 'Add a member' })
    await user.click(within(sheet).getByRole('button', { name: 'Add member' }))
    expect(await within(sheet).findByText('Enter the member’s name')).toBeInTheDocument()
    expect(created).toEqual([])
    await user.type(within(sheet).getByLabelText(/^Full name/), 'Priya Shah')
    await user.click(within(sheet).getByRole('button', { name: 'Add member' }))

    await waitFor(() => {
      expect(created).toEqual([{ fullName: 'Priya Shah', consentEmail: true }])
    })
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Add a member' })).not.toBeInTheDocument()
    })
    expect((await screen.findAllByRole('link', { name: 'Priya Shah' }))[0]).toBeInTheDocument()
  })

  it('checks the email and phone before sending', async () => {
    const post = vi.fn(() => ({ status: 201, data: member() }))
    api({ 'GET /community/members': ok(page([])), 'POST /community/members': post })
    const user = userEvent.setup()
    render('/admin/members?new=1')

    const sheet = await screen.findByRole('dialog', { name: 'Add a member' })
    await user.type(within(sheet).getByLabelText(/^Full name/), 'Priya')
    await user.type(within(sheet).getByLabelText('Email'), 'not-an-email')
    await user.type(within(sheet).getByLabelText('Phone'), 'abc')
    await user.click(within(sheet).getByRole('button', { name: 'Add member' }))
    expect(await within(sheet).findByText('Enter a valid email address')).toBeInTheDocument()
    expect(within(sheet).getByText('Enter a valid phone number')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('asks whether an address is shared when another member already has it, then sends the answer', async () => {
    const sent: Array<Record<string, unknown>> = []
    api({
      'GET /community/members': ok(page([])),
      'POST /community/members': (config) => {
        sent.push(body(config))
        return sent.length === 1 ? problem(409, 'DUPLICATE_EMAIL') : { status: 201, data: member() }
      },
    })
    const user = userEvent.setup()
    render('/admin/members?new=1')

    const sheet = await screen.findByRole('dialog', { name: 'Add a member' })
    await user.type(within(sheet).getByLabelText(/^Full name/), 'Priya')
    await user.type(within(sheet).getByLabelText('Email'), 'family@example.test')
    await user.click(within(sheet).getByRole('button', { name: 'Add member' }))
    expect(
      await within(sheet).findByText(/Another member already uses this address/),
    ).toBeInTheDocument()
    await user.click(
      within(sheet).getByRole('checkbox', { name: /these members share this address/ }),
    )
    await user.click(within(sheet).getByRole('button', { name: 'Add member' }))
    await waitFor(() => {
      expect(sent).toHaveLength(2)
    })
    expect(sent[1]).toMatchObject({ email: 'family@example.test', allowDuplicateEmail: true })
  })

  it('explains a plan limit in a dialog with the numbers and a way forward', async () => {
    api({
      'GET /community/members': ok(page([])),
      'POST /community/members': () =>
        problem(
          402,
          'PLAN_LIMIT_EXCEEDED',
          'The Starter plan allows up to 50 members and this community has 50. Upgrade the plan to add more.',
          {
            limit: 'max_members',
            limitValue: 50,
            current: 50,
            plan: 'STARTER',
          },
        ),
    })
    const user = userEvent.setup()
    render('/admin/members?new=1')

    const sheet = await screen.findByRole('dialog', { name: 'Add a member' })
    await user.type(within(sheet).getByLabelText(/^Full name/), 'Priya')
    await user.click(within(sheet).getByRole('button', { name: 'Add member' }))

    const dialog = await screen.findByRole('dialog', {
      name: 'Your plan has no room for more members',
    })
    expect(within(dialog).getByText(/allows up to 50 members/)).toBeInTheDocument()
    expect(within(dialog).getByText(/50 of 50/)).toBeInTheDocument()
    expect(within(dialog).getByText(/on the STARTER plan/)).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: 'Ask about upgrading' })).toHaveAttribute(
      'href',
      '/admin/support',
    )
  })

  it('edits only what changed, and an emptied box goes out as empty', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /community/members': ok(page([member()])),
      'PATCH /community/members/m-1': (config) => {
        sent = body(config)
        return { status: 200, data: member({ phone: null }) }
      },
    })
    const user = userEvent.setup()
    render('/admin/members')

    await user.click((await screen.findAllByRole('button', { name: 'Actions for Asha Rao' }))[0]!)
    await user.click(await screen.findByRole('menuitem', { name: 'Edit' }))
    const sheet = await screen.findByRole('dialog', { name: 'Edit Asha Rao' })
    expect(within(sheet).getByLabelText(/^Full name/)).toHaveValue('Asha Rao')
    await user.click(within(sheet).getByRole('button', { name: 'Save changes' }))
    // Nothing changed: nothing is sent.
    expect(sent).toBeUndefined()
    await user.clear(within(sheet).getByLabelText('Phone'))
    await user.click(within(sheet).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => {
      expect(sent).toEqual({ phone: '' })
    })
  })

  it('deactivates with a reason, and activates without one', async () => {
    const calls: Array<[string, Record<string, unknown>]> = []
    api({
      'GET /community/members': ok(
        page([
          member(),
          member({ id: 'm-2', memberNo: 'M-0002', fullName: 'Ravi Menon', status: 'INACTIVE' }),
        ]),
      ),
      'POST /community/members/m-1/deactivate': (config) => {
        calls.push(['deactivate', body(config)])
        return { status: 200, data: member({ status: 'INACTIVE' }) }
      },
      'POST /community/members/m-2/activate': (config) => {
        calls.push(['activate', body(config)])
        return { status: 200, data: member({ id: 'm-2' }) }
      },
    })
    const user = userEvent.setup()
    render('/admin/members')

    await user.click((await screen.findAllByRole('button', { name: 'Actions for Asha Rao' }))[0]!)
    await user.click(await screen.findByRole('menuitem', { name: 'Deactivate' }))
    const dialog = await screen.findByRole('dialog', { name: 'Deactivate Asha Rao?' })
    expect(within(dialog).getByRole('button', { name: 'Deactivate' })).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Moved away')
    await user.click(within(dialog).getByRole('button', { name: 'Deactivate' }))
    await waitFor(() => {
      expect(calls).toEqual([['deactivate', { reason: 'Moved away' }]])
    })

    await user.click((await screen.findAllByRole('button', { name: 'Actions for Ravi Menon' }))[0]!)
    await user.click(await screen.findByRole('menuitem', { name: 'Activate' }))
    const again = await screen.findByRole('dialog', { name: 'Activate Ravi Menon?' })
    await user.click(within(again).getByRole('button', { name: 'Activate' }))
    await waitFor(() => {
      expect(calls.at(-1)).toEqual(['activate', {}])
    })
  })

  it('switches add, import and the row actions off while suspended', async () => {
    api({
      'GET /community/plan': ok(plan({ communityStatus: 'SUSPENDED' })),
      'GET /community/members': ok(page([member()])),
    })
    const user = userEvent.setup()
    render('/admin/members')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Add member' })).toBeDisabled()
    })
    expect(screen.getByRole('button', { name: 'Import CSV' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeEnabled() // reading is fine
    await user.click((await screen.findAllByRole('button', { name: 'Actions for Asha Rao' }))[0]!)
    expect(await screen.findByRole('menuitem', { name: 'Edit' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })
})

describe('Plan-limit meter', () => {
  const withMembers = (used: number, limit: number | null) =>
    api({
      'GET /community/plan': ok(
        plan({ usage: { members: used, emailsThisMonth: 0 }, limits: { max_members: limit } }),
      ),
      'GET /community/members': ok(page([])),
    })

  it('shows “43 of 50 members” with a bar and no nagging below 90%', async () => {
    withMembers(43, 50)
    render('/admin/members')
    const meter = await screen.findByRole('region', { name: 'Plan limit for members' })
    expect(within(meter).getByText(/43 of 50/)).toBeInTheDocument()
    const bar = within(meter).getByRole('progressbar', { name: 'Members used' })
    expect(bar).toHaveAttribute('aria-valuenow', '43')
    expect(bar).toHaveAttribute('aria-valuemax', '50')
    expect(within(meter).queryByRole('link')).not.toBeInTheDocument()
  })

  it('suggests a bigger plan from 90%', async () => {
    withMembers(45, 50)
    render('/admin/members')
    const meter = await screen.findByRole('region', { name: 'Plan limit for members' })
    expect(within(meter).getByText(/close to the limit/)).toBeInTheDocument()
    expect(within(meter).getByRole('link', { name: 'Ask about a bigger plan' })).toHaveAttribute(
      'href',
      '/admin/support',
    )
  })

  it('says plainly that the limit is reached', async () => {
    withMembers(50, 50)
    render('/admin/members')
    expect(
      await screen.findByText(/reached the limit, so no more members can be added/),
    ).toBeInTheDocument()
  })

  it('has nothing to warn about when there is no limit', async () => {
    withMembers(200, null)
    render('/admin/members')
    expect(await screen.findByText(/The Starter plan has no limit/)).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })
})

describe('Member export and import', () => {
  it('exports the list with the filters on screen', async () => {
    const seen: string[] = []
    const createUrl = vi.fn(() => 'blob:members')
    URL.createObjectURL = createUrl
    URL.revokeObjectURL = vi.fn()
    api({
      'GET /community/members': ok(page([member()])),
      'GET /community/members/export': (config) => {
        seen.push(query(config))
        return { status: 200, data: new Blob(['a,b']) }
      },
    })
    const user = userEvent.setup()
    render('/admin/members?status=ACTIVE&q=asha')
    await user.click(await screen.findByRole('button', { name: 'Export CSV' }))
    await waitFor(() => {
      expect(seen).toHaveLength(1)
    })
    expect(seen[0]).toContain('status=ACTIVE')
    expect(seen[0]).toContain('q=asha')
    expect(createUrl).toHaveBeenCalled()
  })

  const fileReport = (over: Partial<ImportReport['members']> = {}): ImportReport['members'] => ({
    provided: true,
    totalRows: 3,
    validRows: 2,
    invalidRows: 1,
    errors: [],
    warnings: [],
    valid: [],
    invalid: [{ row: 3, key: 'M-3', errors: ['email is not valid'] }],
    ...over,
  })
  const none: ImportReport['members'] = fileReport({
    provided: false,
    totalRows: 0,
    validRows: 0,
    invalidRows: 0,
    invalid: [],
  })
  const report = (over: Partial<ImportReport> = {}): ImportReport => ({
    batchId: 'b1',
    status: 'DRY_RUN',
    confirmable: true,
    summary: {
      validRows: 2,
      invalidRows: 1,
      newMembers: 2,
      planMemberLimit: 50,
      currentMembers: 10,
    },
    members: fileReport(),
    openingBalances: none,
    openInvoices: none,
    blockingErrors: [],
    warnings: [],
    result: null,
    ...over,
  })

  it('shows the row errors from the dry run, and imports only after skipping them on purpose', async () => {
    let upload: FormData | undefined
    let confirmed: Record<string, unknown> | undefined
    api({
      'GET /community/members': ok(page([])),
      'POST /community/members/import': (config) => {
        upload = config.data as FormData
        return { status: 201, data: report() }
      },
      'POST /community/members/import/b1/confirm': (config) => {
        confirmed = body(config)
        return { status: 200, data: report({ status: 'CONFIRMED' }) }
      },
    })
    const user = userEvent.setup()
    render('/admin/members')

    await user.click(await screen.findByRole('button', { name: 'Import CSV' }))
    const dialog = await screen.findByRole('dialog', { name: 'Import members from a CSV file' })
    expect(within(dialog).getByRole('button', { name: 'Check the file' })).toBeDisabled()
    await user.upload(
      within(dialog).getByLabelText('CSV file'),
      new File(['full_name\nAsha\n'], 'members.csv', { type: 'text/csv' }),
    )
    await user.click(within(dialog).getByRole('button', { name: 'Check the file' }))

    expect(await within(dialog).findByText('email is not valid')).toBeInTheDocument()
    expect(within(dialog).getByText(/Nothing has been saved yet/)).toBeInTheDocument()
    expect(upload?.get('file')).toBeInstanceOf(File)
    const apply = within(dialog).getByRole('button', { name: 'Import now' })
    expect(apply).toBeDisabled()
    await user.click(within(dialog).getByRole('checkbox', { name: /skip the ones with problems/ }))
    await user.click(apply)
    await waitFor(() => {
      expect(confirmed).toEqual({ skipInvalidRows: true })
    })
    expect(await within(dialog).findByText('The members are in the list.')).toBeInTheDocument()
  })

  it('will not import when the server blocks it, for example over the plan limit', async () => {
    api({
      'GET /community/members': ok(page([])),
      'POST /community/members/import': ok(
        report({
          confirmable: false,
          blockingErrors: ['The plan allows 50 members; this would make 90.'],
          members: fileReport({ invalidRows: 0, invalid: [] }),
        }),
        201,
      ),
    })
    const user = userEvent.setup()
    render('/admin/members')

    await user.click(await screen.findByRole('button', { name: 'Import CSV' }))
    const dialog = await screen.findByRole('dialog', { name: 'Import members from a CSV file' })
    await user.upload(
      within(dialog).getByLabelText('CSV file'),
      new File(['x'], 'members.csv', { type: 'text/csv' }),
    )
    await user.click(within(dialog).getByRole('button', { name: 'Check the file' }))
    expect(await within(dialog).findByText(/would make 90/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Import now' })).toBeDisabled()
  })
})

// ---- a member's page --------------------------------------------------------------------------

describe('Member detail', () => {
  const detail = (): Record<string, MockHandler> => ({
    'GET /community/members/m-1': ok(member()),
    'GET /community/invoices': ok(
      page([
        invoice(),
        invoice({
          id: 'inv-2',
          invoiceNo: 'INV-0002',
          status: 'PAID',
          amount: '1000.00',
          amountPaid: '1000.00',
          balance: '0.00',
        }),
        invoice({ id: 'inv-3', invoiceNo: 'INV-0003', status: 'CANCELLED', balance: '999.00' }),
      ]),
    ),
    'GET /community/payments': ok(
      page([
        {
          id: 'p-1',
          kind: 'PAYMENT',
          invoiceId: 'inv-1',
          invoiceNo: 'INV-0001',
          memberId: 'm-1',
          memberNo: 'M-0001',
          payerName: null,
          amount: '500.00',
          method: 'UPI',
          reference: null,
          receivedOn: '2026-09-20',
          reversedOf: null,
          reversalReason: null,
          reversed: false,
          receiptId: 'r-1',
          receiptNo: 'R-0001',
          createdAt: '2026-09-20T10:00:00Z',
        },
      ]),
    ),
    'GET /community/complaints': ok(
      page([
        {
          id: 'c-1',
          subject: 'Lift is broken',
          description: '',
          status: 'OPEN',
          priority: 'HIGH',
          category: null,
          memberId: 'm-1',
          memberNo: 'M-0001',
          memberName: 'Asha Rao',
          assignedTo: null,
          assignedToName: null,
          createdAt: '2026-10-01T10:00:00Z',
          updatedAt: '2026-10-01T10:00:00Z',
          resolvedAt: null,
          closedAt: null,
          ageDays: 17,
          slaTargetDays: 7,
          slaBreached: true,
          commentCount: 0,
          emailOutcome: null,
        },
      ]),
    ),
    'GET /community/members/m-1/communications': ok([
      {
        id: 'e-1',
        template: 'invoice-reminder',
        status: 'SENT',
        createdAt: '2026-10-10T08:00:00Z',
        sentAt: '2026-10-10T08:01:00Z',
      },
      {
        id: 'e-2',
        template: 'member-welcome',
        status: 'FAILED',
        createdAt: '2026-04-01T08:00:00Z',
        sentAt: null,
      },
    ]),
  })

  it('shows the profile, and every other tab on its own address; passes axe', async () => {
    api(detail())
    const user = userEvent.setup()
    const { container, router } = render('/admin/members/m-1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Asha Rao' })).toBeInTheDocument()
    expect(screen.getByText('asha@example.test')).toBeInTheDocument()
    expect(screen.getByText('Agrees to receive emails')).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('tab', { name: 'Dues statement' }))
    expect(router.state.location.search).toBe('?tab=dues')
    // The cancelled invoice is not part of what is owed.
    const owed = (await screen.findByText('Outstanding:')).parentElement
    expect(owed).toHaveTextContent('₹2,000.00')
    expect(owed).not.toHaveTextContent('₹2,999.00')
    expect(screen.getByRole('row', { name: /INV-0002/ })).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Payments' }))
    expect(await screen.findByText('R-0001')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Complaints' }))
    expect(await screen.findByRole('link', { name: 'Lift is broken' })).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Communication' }))
    expect(await screen.findByText('Payment reminder')).toBeInTheDocument()
    expect(screen.getByText('Welcome email')).toBeInTheDocument()
    expect(screen.getByText(/Sent .*2026/)).toBeInTheDocument()
  })

  it('has an empty state in each tab', async () => {
    api({
      'GET /community/members/m-1': ok(member({ email: null })),
      'GET /community/invoices': ok(page([])),
      'GET /community/payments': ok(page([])),
      'GET /community/complaints': ok(page([])),
      'GET /community/members/m-1/communications': ok([]),
    })
    const user = userEvent.setup()
    render('/admin/members/m-1?tab=dues')
    expect(await screen.findByText('No bills yet')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Payments' }))
    expect(await screen.findByText('No payments yet')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Complaints' }))
    expect(await screen.findByText('No complaints')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Communication' }))
    expect(
      await screen.findByText('This member has no email address, so nothing is sent.'),
    ).toBeInTheDocument()
  })

  it('shows an error with a retry when the member cannot be loaded', async () => {
    api({ 'GET /community/members/m-1': () => problem(404, 'NOT_FOUND') })
    render('/admin/members/m-1')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('shows one tab failing without losing the rest', async () => {
    api({ ...detail(), 'GET /community/payments': () => problem(500, 'INTERNAL_ERROR') })
    const user = userEvent.setup()
    render('/admin/members/m-1')
    await screen.findByRole('heading', { level: 1, name: 'Asha Rao' })
    await user.click(screen.getByRole('tab', { name: 'Payments' }))
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Profile' }))
    expect(screen.getByText('asha@example.test')).toBeInTheDocument()
  })
})
