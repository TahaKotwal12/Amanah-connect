import { cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { expectNoA11yViolations } from '@/test/a11y'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import { emptySuperadminApi } from '@/test/superadminApi'
import type {
  CommunityListItem,
  CommunityOverview,
  CommunityView,
  ImportReport,
  PageResponse,
  PlanView,
} from '@/types'

configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 20_000 })

const ok =
  (data: unknown, status = 200): MockHandler =>
  () => ({ status, data })
const body = (config: InternalAxiosRequestConfig) =>
  JSON.parse(config.data as string) as Record<string, unknown>
const query = (config: InternalAxiosRequestConfig) => (config.params as URLSearchParams).toString()

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

// ---- fixtures ---------------------------------------------------------------------------------

const PLAN: PlanView = {
  id: 'plan-1',
  code: 'STARTER',
  name: 'Starter',
  priceMonthly: '499.00',
  priceYearly: '4990.00',
  limits: { max_members: 100 },
  features: {},
  publicPlan: true,
  active: true,
  sortOrder: 1,
  communityCount: 1,
  createdAt: '2026-01-01T00:00:00Z',
}
const PLAN_B: PlanView = { ...PLAN, id: 'plan-2', code: 'GROWTH', name: 'Growth' }

function page<T>(items: T[]): PageResponse<T> {
  return { items, total: items.length, page: 0, size: 20 }
}
const item = (over: Partial<CommunityListItem> = {}): CommunityListItem => ({
  id: 'com-1',
  name: 'Lotus Residents',
  slug: 'lotus',
  status: 'ACTIVE',
  plan: { id: 'plan-1', code: 'STARTER', name: 'Starter' },
  memberCount: 42,
  subscriptionEndsOn: '2026-12-31',
  subscriptionStatus: 'ACTIVE',
  ownerName: 'Asha Rao',
  ownerEmail: 'asha@example.test',
  createdAt: '2026-01-05T10:00:00Z',
  ...over,
})
const community = (over: Partial<CommunityView> = {}): CommunityView => ({
  id: 'com-1',
  name: 'Lotus Residents',
  slug: 'lotus',
  status: 'ACTIVE',
  statusReason: null,
  statusChangedAt: null,
  contactName: 'Asha Rao',
  contactEmail: 'asha@example.test',
  contactPhone: '+91 98765 43210',
  addressLine1: '12 Lake Road',
  addressLine2: null,
  city: 'Pune',
  state: 'Maharashtra',
  postalCode: '411001',
  country: 'IN',
  dateOfEstablishment: '2010-04-01',
  plan: { id: 'plan-1', code: 'STARTER', name: 'Starter' },
  currency: 'INR',
  financialYearStartMonth: 4,
  require2fa: false,
  owner: { id: 'u-1', fullName: 'Asha Rao', email: 'asha@example.test', status: 'ACTIVE' },
  memberCount: 42,
  subscriptionEndsOn: '2026-12-31',
  subscriptionStatus: 'ACTIVE',
  version: 3,
  createdAt: '2026-01-05T10:00:00Z',
  ...over,
})

// ---- list -------------------------------------------------------------------------------------

describe('Communities list', () => {
  it('shows each community with status, plan and owner, and passes axe', async () => {
    api({
      'GET /admin/communities': ok(
        page([item(), item({ id: 'com-2', name: 'Maple Court', status: 'SUSPENDED' })]),
      ),
    })
    const { container } = renderApp('/superadmin/communities')

    expect((await screen.findAllByRole('link', { name: 'Lotus Residents' }))[0]).toHaveAttribute(
      'href',
      '/superadmin/communities/com-1',
    )
    expect(screen.getAllByText('Suspended').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Asha Rao').length).toBeGreaterThan(0)
    await expectNoA11yViolations(container)
  })

  it('sends search, status and plan filters from the address, newest first by default', async () => {
    const seen: string[] = []
    api({
      'GET /admin/communities': (config) => {
        seen.push(query(config))
        return { status: 200, data: page([]) }
      },
    })
    renderApp('/superadmin/communities?q=lotus&status=SUSPENDED&plan=GROWTH')
    expect(await screen.findByText('No communities match')).toBeInTheDocument()
    const last = seen.at(-1) ?? ''
    expect(last).toContain('q=lotus')
    expect(last).toContain('status=SUSPENDED')
    expect(last).toContain('plan=GROWTH')
    expect(last).toContain('sort=createdAt%2Cdesc')
  })

  it('has an empty state with the next step, and an error state with a retry', async () => {
    api({ 'GET /admin/communities': ok(page([])) })
    renderApp('/superadmin/communities')
    expect(await screen.findByText('No communities yet')).toBeInTheDocument()
    cleanup()
    mock?.restore()

    api({ 'GET /admin/communities': () => problem(500, 'INTERNAL_ERROR') })
    renderApp('/superadmin/communities')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('suspends from the row menu only with a reason', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /admin/communities': ok(page([item()])),
      'POST /admin/communities/com-1/suspend': (config) => {
        sent = body(config)
        return { status: 200, data: community({ status: 'SUSPENDED' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities')

    await user.click(
      (await screen.findAllByRole('button', { name: 'Actions for Lotus Residents' }))[0]!,
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Suspend' }))
    const dialog = await screen.findByRole('dialog', { name: 'Suspend Lotus Residents?' })
    const confirm = within(dialog).getByRole('button', { name: 'Suspend' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Unpaid for three months')
    await user.click(confirm)
    await waitFor(() => {
      expect(sent).toEqual({ reason: 'Unpaid for three months' })
    })
  })
})

// ---- create wizard ----------------------------------------------------------------------------

describe('Create community wizard', () => {
  const base = (): Record<string, MockHandler> => ({
    'GET /admin/communities': ok(page([])),
    'GET /admin/plans': ok([
      PLAN,
      PLAN_B,
      { ...PLAN, id: 'plan-3', code: 'OLD', name: 'Old', active: false },
    ]),
  })

  it('checks each step before moving on, lists only plans on offer, and reviews before creating', async () => {
    let created: Record<string, unknown> | undefined
    api({
      ...base(),
      'POST /admin/communities': (config) => {
        created = body(config)
        return { status: 201, data: community({ id: 'com-9', name: 'New Co' }) }
      },
      'GET /admin/communities/com-9': ok(community({ id: 'com-9', name: 'New Co' })),
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities?new=1')

    const wizard = await screen.findByRole('dialog', { name: 'New community' })
    // Step 1 refuses an empty name and a malformed email.
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    expect(await within(wizard).findByText('Enter the community name')).toBeInTheDocument()
    await user.type(within(wizard).getByLabelText(/^Community name/), 'New Co')
    await user.type(within(wizard).getByLabelText('Contact email'), 'not-an-email')
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    expect(await within(wizard).findByText('Enter a valid email address')).toBeInTheDocument()
    await user.clear(within(wizard).getByLabelText('Contact email'))
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))

    // Step 2: a plan that is not offered cannot be picked.
    expect(await within(wizard).findByRole('radio', { name: /Starter/ })).toBeInTheDocument()
    expect(within(wizard).queryByRole('radio', { name: /Old/ })).not.toBeInTheDocument()
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    expect(await within(wizard).findByText('Choose a plan')).toBeInTheDocument()
    await user.click(within(wizard).getByRole('radio', { name: /Growth/ }))
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))

    // Step 3: owner.
    await user.click(await within(wizard).findByRole('button', { name: 'Next' }))
    expect(await within(wizard).findByText('Enter the owner’s name')).toBeInTheDocument()
    await user.type(within(wizard).getByLabelText(/^Owner’s name/), 'Ravi Menon')
    await user.type(within(wizard).getByLabelText(/^Owner’s email/), 'ravi@example.test')
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))

    // Step 4: review, then create. Empty optional boxes are not sent.
    expect(
      await within(wizard).findByText('ravi@example.test', { exact: false }),
    ).toBeInTheDocument()
    expect(created).toBeUndefined()
    await user.click(within(wizard).getByRole('button', { name: 'Create community' }))
    await waitFor(() => {
      expect(created).toBeDefined()
    })
    expect(created).toEqual({
      name: 'New Co',
      planId: 'plan-2',
      ownerName: 'Ravi Menon',
      ownerEmail: 'ravi@example.test',
      financialYearStartMonth: 4,
      country: 'IN',
    })
    // Lands on the new community's page.
    expect(await screen.findByRole('heading', { level: 1, name: 'New Co' })).toBeInTheDocument()
  })

  it('goes back to the step that owns a field the server rejected, and shows its message there', async () => {
    api({
      ...base(),
      'POST /admin/communities': () =>
        problem(400, 'VALIDATION_FAILED', 'Invalid request', {
          errors: [{ field: 'slug', message: 'is already taken' }],
        }),
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities?new=1')

    const wizard = await screen.findByRole('dialog', { name: 'New community' })
    await user.type(within(wizard).getByLabelText(/^Community name/), 'New Co')
    await user.type(within(wizard).getByLabelText(/^Web address name/), 'taken')
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    await user.click(await within(wizard).findByRole('radio', { name: /Starter/ }))
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    await user.type(await within(wizard).findByLabelText(/^Owner’s name/), 'Ravi')
    await user.type(within(wizard).getByLabelText(/^Owner’s email/), 'ravi@example.test')
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    await user.click(await within(wizard).findByRole('button', { name: 'Create community' }))

    expect(await within(wizard).findByText('is already taken')).toBeInTheDocument()
    expect(within(wizard).getByLabelText(/^Web address name/)).toBeInTheDocument()
  })

  it('opens pre-filled from a lead and links the new community to it', async () => {
    let created: Record<string, unknown> | undefined
    api({
      ...base(),
      'GET /admin/leads/lead-1/community-draft': ok({
        request: {
          name: 'Green Park Society',
          planId: '',
          ownerName: 'Meera Nair',
          ownerEmail: 'meera@example.test',
          leadId: 'lead-1',
        },
        suggestedPlanCode: 'GROWTH',
      }),
      'POST /admin/communities': (config) => {
        created = body(config)
        return { status: 201, data: community({ id: 'com-9', name: 'Green Park Society' }) }
      },
      'GET /admin/communities/com-9': ok(community({ id: 'com-9', name: 'Green Park Society' })),
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities?lead=lead-1')

    const wizard = await screen.findByRole('dialog', { name: 'New community' })
    expect(within(wizard).getByLabelText(/^Community name/)).toHaveValue('Green Park Society')
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    // The lead's suggested plan is already chosen.
    expect(await within(wizard).findByRole('radio', { name: /Growth/ })).toBeChecked()
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    expect(await within(wizard).findByLabelText(/^Owner’s email/)).toHaveValue('meera@example.test')
    await user.click(within(wizard).getByRole('button', { name: 'Next' }))
    expect(
      await within(wizard).findByText('The lead will be marked as converted.'),
    ).toBeInTheDocument()
    await user.click(within(wizard).getByRole('button', { name: 'Create community' }))
    await waitFor(() => {
      expect(created).toMatchObject({ leadId: 'lead-1', planId: 'plan-2' })
    })
  })
})

// ---- detail -----------------------------------------------------------------------------------

describe('Community detail', () => {
  const detail = (over: Partial<CommunityView> = {}): Record<string, MockHandler> => ({
    'GET /admin/communities/com-1': ok(community(over)),
    'GET /admin/plans': ok([PLAN, PLAN_B]),
  })

  it('opens on the overview, keeps the tab in the address, and never loads the audited overview by itself', async () => {
    const mocked = api(detail())
    const user = userEvent.setup()
    const { container, router } = renderApp('/superadmin/communities/com-1')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Lotus Residents' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Overview', selected: true })).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Admin and contact' }))
    expect(router.state.location.search).toBe('?tab=contact')
    expect(await screen.findByLabelText('Contact email')).toHaveValue('asha@example.test')
    expect(mocked.count('GET /admin/communities/com-1/overview')).toBe(0)
    await expectNoA11yViolations(container)
  })

  it('has a not-found style error with retry when the community cannot be loaded', async () => {
    api({ 'GET /admin/communities/com-1': () => problem(404, 'NOT_FOUND') })
    renderApp('/superadmin/communities/com-1')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('saves only what changed, with the version it loaded', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      ...detail(),
      'PATCH /admin/communities/com-1': (config) => {
        sent = body(config)
        return { status: 200, data: community({ city: 'Mumbai', version: 4 }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities/com-1?tab=contact')

    const city = await screen.findByLabelText('City')
    await user.clear(city)
    await user.type(city, 'Mumbai')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => {
      expect(sent).toBeDefined()
    })
    expect(sent).toEqual({ city: 'Mumbai', version: 3 })
  })

  it('says so, without losing the form, when someone else saved first', async () => {
    api({
      ...detail(),
      'PATCH /admin/communities/com-1': () => problem(409, 'VERSION_CONFLICT'),
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities/com-1?tab=contact')

    const city = await screen.findByLabelText('City')
    await user.clear(city)
    await user.type(city, 'Mumbai')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText(/Someone else changed this community/)).toBeInTheDocument()
    expect(screen.getByLabelText('City')).toHaveValue('Mumbai')
  })

  it('asks before resetting the admin’s password, then calls the API', async () => {
    const calls: string[] = []
    api({
      ...detail(),
      'POST /admin/communities/com-1/reset-admin-password': () => {
        calls.push('reset')
        return { status: 200, data: { userId: 'u-1', action: 'RESET_EMAIL_SENT' } }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities/com-1?tab=contact')

    await user.click(await screen.findByRole('button', { name: 'Reset password' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(calls).toEqual([])
    await user.click(within(dialog).getByRole('button', { name: 'Send email' }))
    await waitFor(() => {
      expect(calls).toEqual(['reset'])
    })
  })

  it('offers “send the invitation again” while the administrator has not accepted', async () => {
    api(
      detail({
        owner: { id: 'u-1', fullName: 'Asha Rao', email: 'asha@example.test', status: 'INVITED' },
      }),
    )
    renderApp('/superadmin/communities/com-1?tab=contact')
    expect(
      await screen.findByRole('button', { name: 'Send the invitation again' }),
    ).toBeInTheDocument()
  })

  it('archives only with a reason', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      ...detail(),
      'POST /admin/communities/com-1/archive': (config) => {
        sent = body(config)
        return { status: 200, data: community({ status: 'ARCHIVED' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities/com-1')

    await user.click(await screen.findByRole('button', { name: 'Actions' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Archive' }))
    const dialog = await screen.findByRole('dialog', { name: 'Archive Lotus Residents?' })
    expect(within(dialog).getByRole('button', { name: 'Archive' })).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Closed down')
    await user.click(within(dialog).getByRole('button', { name: 'Archive' }))
    await waitFor(() => {
      expect(sent).toEqual({ reason: 'Closed down' })
    })
  })

  it('lists payment history on the plan tab, and changes the plan with the loaded version', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      ...detail(),
      'GET /admin/communities/com-1/subscriptions': ok([
        {
          id: 's1',
          planCode: 'STARTER',
          planName: 'Starter',
          periodStart: '2026-01-01',
          periodEnd: '2026-12-31',
          amount: '4990.00',
          paidOn: '2026-01-02',
          reference: 'UTR1',
          status: 'ACTIVE',
        },
      ]),
      'PATCH /admin/communities/com-1': (config) => {
        sent = body(config)
        return { status: 200, data: community({ version: 4 }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities/com-1?tab=plan')

    expect(await screen.findByText('₹4,990.00')).toBeInTheDocument()
    expect(screen.getByText('UTR1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Change plan' })).toBeDisabled() // nothing chosen yet
    await user.click(screen.getByRole('combobox', { name: 'Plan' }))
    await user.click(await screen.findByRole('option', { name: 'Growth (GROWTH)' }))
    await user.click(screen.getByRole('button', { name: 'Change plan' }))
    await waitFor(() => {
      expect(sent).toEqual({ planId: 'plan-2', version: 3 })
    })
  })
})

// ---- support view -----------------------------------------------------------------------------

describe('Support view', () => {
  const overview: CommunityOverview = {
    community: community(),
    counts: {
      membersActive: 40,
      membersInactive: 2,
      pendingRegistrations: 1,
      openInvoices: 7,
      openComplaints: 0,
      openSupportThreads: 1,
    },
    finance: {
      invoicedOpen: '15000.00',
      collectedOnOpenAndPaid: '5000.00',
      outstanding: '10000.00',
      incomeThisFinancialYear: '120000.00',
      expenseThisFinancialYear: '80000.00',
      netThisFinancialYear: '40000.00',
      financialYearStart: '2026-04-01',
    },
    recentActivity: [
      {
        id: 'a1',
        action: 'INVOICE_ISSUED',
        entityType: 'Invoice',
        entityId: null,
        actorUserId: null,
        actorName: 'Asha Rao',
        at: '2026-10-16T08:00:00Z',
      },
    ],
  }

  it('always shows the banner that the access is logged, with the read-only numbers', async () => {
    const mocked = api({ 'GET /admin/communities/com-1/overview': ok(overview) })
    const { container } = renderApp('/superadmin/communities/com-1/support-view')

    expect(
      await screen.findByText('You are viewing community data (this access is logged).'),
    ).toBeInTheDocument()
    expect(await screen.findByText('₹1,20,000.00')).toBeInTheDocument()
    expect(screen.getByText('INVOICE_ISSUED')).toBeInTheDocument()
    expect(mocked.count('GET /admin/communities/com-1/overview')).toBe(1)
    await expectNoA11yViolations(container)
  })

  it('shows the banner and an error with a retry when the data cannot be read', async () => {
    api({ 'GET /admin/communities/com-1/overview': () => problem(403, 'FORBIDDEN') })
    renderApp('/superadmin/communities/com-1/support-view')
    expect(
      await screen.findByText('You are viewing community data (this access is logged).'),
    ).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })
})

// ---- import -----------------------------------------------------------------------------------

describe('Import wizard', () => {
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
  const none = fileReport({
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
      planMemberLimit: 100,
      currentMembers: 42,
    },
    members: fileReport(),
    openingBalances: none,
    openInvoices: none,
    blockingErrors: [],
    warnings: [],
    result: null,
    ...over,
  })

  async function openImport(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: 'Actions' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Import members and balances' }))
    return screen.findByRole('dialog', { name: 'Import into Lotus Residents' })
  }

  it('shows row errors from the dry run and only imports after skipping them on purpose', async () => {
    let upload: FormData | undefined
    let confirmed: Record<string, unknown> | undefined
    api({
      'GET /admin/communities/com-1': ok(community()),
      'GET /admin/plans': ok([PLAN]),
      'POST /admin/communities/com-1/import': (config) => {
        upload = config.data as FormData
        return { status: 201, data: report() }
      },
      'POST /admin/communities/com-1/import/b1/confirm': (config) => {
        confirmed = body(config)
        return { status: 200, data: report({ status: 'CONFIRMED' }) }
      },
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities/com-1')

    const dialog = await openImport(user)
    expect(within(dialog).getByRole('button', { name: 'Check the files' })).toBeDisabled()
    const file = new File(['member_no,full_name\nM-1,Asha\n'], 'members.csv', { type: 'text/csv' })
    await user.upload(within(dialog).getByLabelText(/^Members/), file)
    await user.click(within(dialog).getByRole('button', { name: 'Check the files' }))

    expect(await within(dialog).findByText('email is not valid')).toBeInTheDocument()
    expect(within(dialog).getByText(/Nothing has been saved yet/)).toBeInTheDocument()
    expect(upload?.get('members')).toBeInstanceOf(File)
    expect(upload?.get('openInvoices')).toBeNull()

    const apply = within(dialog).getByRole('button', { name: 'Import now' })
    expect(apply).toBeDisabled() // there are bad rows
    await user.click(within(dialog).getByRole('checkbox', { name: /skip the ones with problems/ }))
    expect(apply).toBeEnabled()
    await user.click(apply)
    await waitFor(() => {
      expect(confirmed).toEqual({ skipInvalidRows: true })
    })
    expect(await within(dialog).findByText(/The import is finished/)).toBeInTheDocument()
  })

  it('will not import at all when the server reports blocking errors', async () => {
    api({
      'GET /admin/communities/com-1': ok(community()),
      'GET /admin/plans': ok([PLAN]),
      'POST /admin/communities/com-1/import': ok(
        report({
          confirmable: false,
          blockingErrors: ['The plan allows 100 members; this would make 142.'],
          members: fileReport({ invalidRows: 0, invalid: [] }),
        }),
        201,
      ),
    })
    const user = userEvent.setup()
    renderApp('/superadmin/communities/com-1')

    const dialog = await openImport(user)
    await user.upload(
      within(dialog).getByLabelText(/^Members/),
      new File(['x'], 'members.csv', { type: 'text/csv' }),
    )
    await user.click(within(dialog).getByRole('button', { name: 'Check the files' }))
    expect(await within(dialog).findByText(/would make 142/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Import now' })).toBeDisabled()
  })
})
