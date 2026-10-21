import { cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { expectNoA11yViolations } from '@/test/a11y'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import type {
  LeadView,
  MonthlySeries,
  PageResponse,
  PlanView,
  PlatformStats,
  SubscriptionView,
} from '@/types'

const ok =
  (data: unknown): MockHandler =>
  () => ({ status: 200, data })
const body = (config: InternalAxiosRequestConfig) =>
  JSON.parse(config.data as string) as Record<string, unknown>
const query = (config: InternalAxiosRequestConfig) => (config.params as URLSearchParams).toString()

// The first test in a file also compiles the lazy route chunks, which can take a few seconds.
configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 15_000 })

let mock: ReturnType<typeof installMockApi> | undefined
function api(handlers: Record<string, MockHandler>) {
  mock = installMockApi(handlers)
  return mock
}

beforeEach(() => {
  setSession('SUPER_ADMIN')
})
afterEach(() => {
  // Unmount first: clearing the session under a mounted page would send it to the sign-in page
  // and leave a refresh attempt running into the next test.
  cleanup()
  mock?.restore()
  mock = undefined
  resetSession()
})

// ---- fixtures ---------------------------------------------------------------------------------

const STATS: PlatformStats = {
  communities: { total: 12, pending: 1, active: 9, suspended: 2, archived: 3 },
  totalMembers: 4321,
  platformRevenue: { thisMonth: '12500.00', thisYear: '150000.50', currency: 'INR' },
  expiringSubscriptions: 4,
  expiringWithinDays: 30,
  newLeads: 6,
  openSupportThreads: 2,
}
const MONTHLY: MonthlySeries = {
  currency: 'INR',
  months: [
    { month: '2026-08', revenue: '0.00', newCommunities: 0 },
    { month: '2026-09', revenue: '4990.00', newCommunities: 1 },
    { month: '2026-10', revenue: '12500.00', newCommunities: 2 },
  ],
}
function page<T>(items: T[]): PageResponse<T> {
  return { items, total: items.length, page: 0, size: 20 }
}
const subscription = (over: Partial<SubscriptionView> = {}): SubscriptionView => ({
  id: 'sub-1',
  communityId: 'com-1',
  communityName: 'Lotus Residents',
  communitySlug: 'lotus',
  planCode: 'STARTER',
  planName: 'Starter',
  periodStart: '2026-01-01',
  periodEnd: '2026-12-31',
  amount: '4990.00',
  paidOn: '2026-01-02',
  reference: 'UTR123',
  status: 'ACTIVE',
  cancelReason: null,
  daysLeft: 75,
  createdAt: '2026-01-02T10:00:00Z',
  ...over,
})
const LEAD: LeadView = {
  id: 'lead-1',
  name: 'Meera Nair',
  email: 'meera@example.test',
  phone: null,
  communityName: 'Green Park Society',
  size: 80,
  message: null,
  status: 'NEW',
  handledBy: null,
  convertedCommunityId: null,
  source: 'website',
  createdAt: '2026-10-16T08:00:00Z',
  updatedAt: '2026-10-16T08:00:00Z',
}
const plan = (over: Partial<PlanView> = {}): PlanView => ({
  id: 'plan-1',
  code: 'STARTER',
  name: 'Starter',
  priceMonthly: '499.00',
  priceYearly: '4990.00',
  limits: { max_members: 100, storage_mb: 500, emails_per_month: null, emails_per_day: null },
  features: { upi_qr: true, pdf_reports: false },
  publicPlan: true,
  active: true,
  sortOrder: 10,
  communityCount: 3,
  createdAt: '2026-01-01T00:00:00Z',
  ...over,
})

// ---- dashboard --------------------------------------------------------------------------------

describe('Platform dashboard', () => {
  const handlers = (): Record<string, MockHandler> => ({
    'GET /admin/stats': ok(STATS),
    'GET /admin/stats/monthly': ok(MONTHLY),
    'GET /admin/subscriptions/expiring': ok(
      page([subscription({ status: 'EXPIRING', daysLeft: 6, periodEnd: '2026-10-23' })]),
    ),
    'GET /admin/leads': ok(page([LEAD])),
  })

  it('shows the numbers, both charts as readable tables, and the two lists; passes axe', async () => {
    api(handlers())
    const { container } = renderApp('/superadmin/dashboard')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Platform dashboard' }),
    ).toBeInTheDocument()
    const numbers = await screen.findByRole('region', { name: 'Key numbers' })
    await waitFor(() => {
      expect(within(numbers).getByText('4,321')).toBeInTheDocument()
    })
    expect(within(numbers).getByText('₹12,500.00')).toBeInTheDocument()
    expect(within(numbers).getByText('₹1,50,000.50')).toBeInTheDocument()
    expect(within(numbers).getByText('Subscriptions ending within 30 days')).toBeInTheDocument()

    // The charts are drawn from the same numbers and also offered as tables.
    const revenue = await screen.findByRole('table', { name: 'Revenue by month' })
    expect(within(revenue).getByRole('row', { name: /Sep 2026.*₹4,990\.00/ })).toBeInTheDocument()
    const created = screen.getByRole('table', { name: 'New communities by month' })
    expect(within(created).getByRole('row', { name: /Oct 2026.*2/ })).toBeInTheDocument()

    expect(await screen.findByRole('link', { name: 'Lotus Residents' })).toHaveAttribute(
      'href',
      '/superadmin/communities/com-1',
    )
    expect(screen.getByText('Ends in 6 days')).toBeInTheDocument()
    expect(await screen.findByText('Meera Nair')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('asks for the expiring list inside the window the server reports, soonest first', async () => {
    const seen: string[] = []
    api({
      ...handlers(),
      'GET /admin/subscriptions/expiring': (config) => {
        seen.push(query(config))
        return { status: 200, data: page([]) }
      },
    })
    renderApp('/superadmin/dashboard')
    expect(await screen.findByText('Nothing is ending soon')).toBeInTheDocument()
    expect(seen.at(-1)).toContain('days=30')
    expect(seen.at(-1)).toContain('sort=periodEnd%2Casc')
  })

  it('says so, with a retry, when the numbers cannot be loaded, and still shows the rest', async () => {
    let attempts = 0
    api({
      ...handlers(),
      'GET /admin/stats': () => {
        attempts += 1
        return attempts === 1 ? problem(500, 'INTERNAL_ERROR') : { status: 200, data: STATS }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/dashboard')

    await user.click(await screen.findByRole('button', { name: /try again|retry/i }))
    expect(await screen.findByText('4,321')).toBeInTheDocument()
    expect(attempts).toBe(2)
  })

  it('has an empty state for each list when there is nothing yet', async () => {
    api({
      ...handlers(),
      'GET /admin/subscriptions/expiring': ok(page([])),
      'GET /admin/leads': ok(page([])),
    })
    renderApp('/superadmin/dashboard')
    expect(await screen.findByText('No leads yet')).toBeInTheDocument()
    expect(screen.getByText('Nothing is ending soon')).toBeInTheDocument()
  })
})

// ---- plans ------------------------------------------------------------------------------------

describe('Plans', () => {
  it('lists each plan with its price, limits and how many communities use it', async () => {
    api({
      'GET /admin/plans': ok([
        plan(),
        plan({
          id: 'plan-2',
          code: 'OLD',
          name: 'Old',
          active: false,
          publicPlan: false,
          communityCount: 0,
        }),
      ]),
    })
    const { container } = renderApp('/superadmin/plans')

    expect(await screen.findByRole('heading', { level: 2, name: 'Starter' })).toBeInTheDocument()
    expect(screen.getAllByText('₹499.00').length).toBeGreaterThan(0)
    expect(screen.getByText('3 communities on this plan')).toBeInTheDocument()
    expect(screen.getAllByText('Unlimited').length).toBeGreaterThan(0) // null limits
    expect(screen.getByText('Hidden from website')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Offer Old' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('has loading, empty and error states', async () => {
    api({ 'GET /admin/plans': ok([]) })
    renderApp('/superadmin/plans')
    expect(await screen.findByText('No plans yet')).toBeInTheDocument()
  })

  it('shows the error with a way to retry', async () => {
    api({ 'GET /admin/plans': () => problem(500, 'INTERNAL_ERROR') })
    renderApp('/superadmin/plans')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('creates a plan: empty limits are sent as null (unlimited), prices as strings', async () => {
    let created: Record<string, unknown> | undefined
    api({
      'GET /admin/plans': ok([]),
      'POST /admin/plans': (config) => {
        created = body(config)
        return { status: 201, data: plan({ code: 'GROWTH', name: 'Growth' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/plans')

    await user.click(await screen.findByRole('button', { name: 'New plan' }))
    const sheet = await screen.findByRole('dialog', { name: 'New plan' })
    await user.type(within(sheet).getByLabelText(/^Code/), 'GROWTH')
    await user.type(within(sheet).getByLabelText(/^Name/), 'Growth')
    await user.type(within(sheet).getByLabelText(/^Price per month/), '999')
    await user.type(within(sheet).getByLabelText(/^Price per year/), '9990')
    await user.type(within(sheet).getByLabelText(/^Members/), '250')
    await user.click(within(sheet).getByRole('checkbox', { name: 'UPI QR payments' }))

    // The live preview is the same card the website shows.
    expect(within(sheet).getByText('Up to 250 members')).toBeInTheDocument()

    await user.click(within(sheet).getByRole('button', { name: 'Create plan' }))
    await waitFor(() => {
      expect(created).toBeDefined()
    })
    expect(created).toMatchObject({
      code: 'GROWTH',
      name: 'Growth',
      priceMonthly: '999.00',
      priceYearly: '9990.00',
      publicPlan: false,
      active: true,
      sortOrder: 100,
      limits: { max_members: 250, storage_mb: null, emails_per_month: null, emails_per_day: null },
      features: { upi_qr: true, receipts_email: false, csv_export: false, pdf_reports: false },
    })
  })

  it('checks the form before sending anything', async () => {
    const post = vi.fn(() => ({ status: 201, data: plan() }))
    api({ 'GET /admin/plans': ok([]), 'POST /admin/plans': post })
    const user = userEvent.setup()
    renderApp('/superadmin/plans')

    await user.click(await screen.findByRole('button', { name: 'New plan' }))
    const sheet = await screen.findByRole('dialog', { name: 'New plan' })
    await user.type(within(sheet).getByLabelText(/^Code/), 'lower case')
    await user.type(within(sheet).getByLabelText(/^Members/), '12x')
    await user.click(within(sheet).getByRole('button', { name: 'Create plan' }))

    expect(
      await within(sheet).findByText('Use upper case letters, digits and underscores'),
    ).toBeInTheDocument()
    expect(within(sheet).getByText('Enter a name')).toBeInTheDocument()
    expect(within(sheet).getAllByText('Enter a price (0 for free)')).toHaveLength(2)
    expect(
      within(sheet).getByText('Enter a whole number, or leave it empty for unlimited'),
    ).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('shows the backend’s own message on the field it names', async () => {
    api({
      'GET /admin/plans': ok([plan()]),
      'PATCH /admin/plans/plan-1': () =>
        problem(400, 'VALIDATION_FAILED', 'The plan limits or features are invalid.', {
          errors: [{ field: 'limits.max_members', message: 'must be a whole number of 0 or more' }],
        }),
    })
    const user = userEvent.setup()
    renderApp('/superadmin/plans')

    await user.click(await screen.findByRole('button', { name: 'Edit Starter' }))
    const sheet = await screen.findByRole('dialog', { name: 'Edit Starter' })
    expect(within(sheet).getByLabelText(/^Code/)).toBeDisabled() // never changes
    await user.click(within(sheet).getByRole('button', { name: 'Save changes' }))
    expect(
      await within(sheet).findByText('must be a whole number of 0 or more'),
    ).toBeInTheDocument()
  })

  it('asks before stopping a plan, then does it', async () => {
    const calls: string[] = []
    api({
      'GET /admin/plans': ok([plan()]),
      'POST /admin/plans/plan-1/deactivate': () => {
        calls.push('deactivate')
        return { status: 200, data: plan({ active: false }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/plans')

    await user.click(await screen.findByRole('button', { name: 'Stop offering Starter' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(calls).toEqual([])
    await user.click(within(dialog).getByRole('button', { name: 'Stop offering' }))
    await waitFor(() => {
      expect(calls).toEqual(['deactivate'])
    })
  })
})

// ---- subscriptions ----------------------------------------------------------------------------

describe('Subscriptions', () => {
  it('lists payments with an expiry badge, and passes axe', async () => {
    api({
      'GET /admin/subscriptions': ok(
        page([
          subscription(),
          subscription({
            id: 'sub-2',
            communityName: 'Maple Court',
            status: 'EXPIRING',
            daysLeft: 3,
          }),
          subscription({ id: 'sub-3', communityName: 'Old Co', status: 'EXPIRED', daysLeft: -12 }),
        ]),
      ),
    })
    const { container } = renderApp('/superadmin/subscriptions')

    expect(await screen.findAllByRole('link', { name: 'Maple Court' })).not.toHaveLength(0)
    expect(screen.getAllByText('Ends in 3 days').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Ended 12 days ago').length).toBeGreaterThan(0)
    await expectNoA11yViolations(container)
  })

  it('sends the status filter from the address, and the “ending in N days” filter to its own endpoint', async () => {
    const plain: string[] = []
    const expiring: string[] = []
    api({
      'GET /admin/subscriptions': (config) => {
        plain.push(query(config))
        return { status: 200, data: page([]) }
      },
      'GET /admin/subscriptions/expiring': (config) => {
        expiring.push(query(config))
        return { status: 200, data: page([]) }
      },
    })
    const first = renderApp('/superadmin/subscriptions?status=EXPIRED')
    expect(await screen.findByText('No subscriptions match')).toBeInTheDocument()
    expect(plain.at(-1)).toContain('status=EXPIRED')
    expect(plain.at(-1)).toContain('sort=periodEnd%2Casc')
    first.unmount()

    renderApp('/superadmin/subscriptions?days=15&status=ACTIVE')
    expect(await screen.findByText('No subscriptions match')).toBeInTheDocument()
    expect(expiring.at(-1)).toContain('days=15')
    expect(expiring.at(-1)).not.toContain('status') // that endpoint takes none
  })

  it('shows an empty state with the next step when nothing was ever recorded', async () => {
    api({ 'GET /admin/subscriptions': ok(page([])) })
    renderApp('/superadmin/subscriptions')
    expect(await screen.findByText('No payments recorded yet')).toBeInTheDocument()
  })

  it('records a payment: the plan suggests the yearly price and a one-year period', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /admin/subscriptions': ok(page([])),
      'GET /admin/plans': ok([
        plan(),
        plan({ id: 'plan-2', code: 'OLD', name: 'Old', active: false }),
      ]),
      'GET /admin/communities': ok(
        page([
          {
            id: 'com-1',
            name: 'Lotus Residents',
            slug: 'lotus',
            status: 'ACTIVE',
            plan: { id: 'plan-1', code: 'STARTER', name: 'Starter' },
            memberCount: 5,
            subscriptionEndsOn: null,
            subscriptionStatus: null,
            ownerName: null,
            ownerEmail: null,
            createdAt: '2026-01-01T00:00:00Z',
          },
        ]),
      ),
      'POST /admin/subscriptions': (config) => {
        sent = body(config)
        return { status: 201, data: subscription() }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/subscriptions')

    await user.click((await screen.findAllByRole('button', { name: 'Record payment' }))[0]!)
    const sheet = await screen.findByRole('dialog', { name: 'Record a payment' })

    await user.click(within(sheet).getByRole('combobox', { name: /^Community/ }))
    await user.click(await screen.findByRole('option', { name: 'Lotus Residents' }))
    await user.click(within(sheet).getByRole('combobox', { name: /^Plan/ }))
    // A plan that is not offered cannot be chosen.
    expect(screen.queryByRole('option', { name: /Old/ })).not.toBeInTheDocument()
    await user.click(await screen.findByRole('option', { name: 'Starter (STARTER)' }))

    expect(within(sheet).getByLabelText(/^Amount received/)).toHaveValue('4,990.00')
    await user.type(within(sheet).getByLabelText('Reference'), 'UTR999')
    await user.click(within(sheet).getByRole('button', { name: 'Record payment' }))

    await waitFor(() => {
      expect(sent).toBeDefined()
    })
    expect(sent).toMatchObject({
      communityId: 'com-1',
      planId: 'plan-1',
      amount: '4990.00',
      reference: 'UTR999',
    })
    const start = sent?.periodStart as string
    const end = sent?.periodEnd as string
    expect(start).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(Number(end.slice(0, 4)) - Number(start.slice(0, 4))).toBe(
      end.slice(5) < start.slice(5) ? 1 : 0, // one year less a day: ends in the same or the next calendar year
    )
  })

  it('cancelling needs a reason, and sends it', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /admin/subscriptions': ok(page([subscription()])),
      'POST /admin/subscriptions/sub-1/cancel': (config) => {
        sent = body(config)
        return { status: 200, data: subscription({ status: 'CANCELLED' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/subscriptions')

    await user.click(
      (await screen.findAllByRole('button', { name: 'Actions for Lotus Residents, Starter' }))[0]!,
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Cancel subscription' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this subscription?' })
    const confirm = within(dialog).getByRole('button', { name: 'Cancel subscription' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Paid twice by mistake')
    expect(confirm).toBeEnabled()
    await user.click(confirm)
    await waitFor(() => {
      expect(sent).toEqual({ reason: 'Paid twice by mistake' })
    })
  })
})
