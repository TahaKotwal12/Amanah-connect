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
  InviteView,
  PageResponse,
  PublicInviteView,
  PublicPayView,
  RegistrationView,
} from '@/types'

configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 25_000 })

const ok =
  (data: unknown, status = 200): MockHandler =>
  () => ({ status, data })
const body = (config: InternalAxiosRequestConfig) =>
  JSON.parse(config.data as string) as Record<string, unknown>
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
  vi.restoreAllMocks()
})

// ---- fixtures ---------------------------------------------------------------------------------

const invite = (over: Partial<InviteView> = {}): InviteView => ({
  id: 'i-1',
  state: 'ACTIVE',
  expiresAt: '2026-11-01T00:00:00Z',
  maxUses: 100,
  usedCount: 4,
  defaultGroup: null,
  invitedEmail: null,
  revokedAt: null,
  createdAt: '2026-10-17T08:00:00Z',
  ...over,
})
const registration = (over: Partial<RegistrationView> = {}): RegistrationView => ({
  id: 'r-1',
  fullName: 'Priya Shah',
  email: 'priya@example.test',
  phone: '+91 90000 00000',
  group: 'Block B',
  consentEmail: true,
  status: 'PENDING',
  createdAt: '2026-10-17T09:00:00Z',
  reviewedAt: null,
  rejectionReason: null,
  memberId: null,
  memberNo: null,
  emailUsedByMemberNo: null,
  ...over,
})
const QR = 'iVBORw0KGgoAAAANSUhEUg=='

// ---- invite links -----------------------------------------------------------------------------

describe('Invite links', () => {
  it('lists links with their state, uses and expiry, and passes axe', async () => {
    api({
      'GET /community/member-invites': ok(
        page([
          invite(),
          invite({ id: 'i-2', state: 'EXPIRED', usedCount: 0 }),
          invite({ id: 'i-3', state: 'USED_UP', usedCount: 100 }),
          invite({ id: 'i-4', state: 'REVOKED', invitedEmail: 'ravi@example.test' }),
        ]),
      ),
    })
    const { container } = render('/admin/invites')
    expect((await screen.findAllByText('Anyone with the link')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('Working').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Used up').length).toBeGreaterThan(0)
    expect(screen.getAllByText('4 of 100').length).toBeGreaterThan(0)
    expect(screen.getAllByText('ravi@example.test').length).toBeGreaterThan(0)
    await expectNoA11yViolations(container)
  })

  it('has an empty state, and an error with a retry', async () => {
    api({ 'GET /community/member-invites': ok(page([])) })
    render('/admin/invites')
    expect(await screen.findByText('No invite links yet')).toBeInTheDocument()
    cleanup()
    mock?.restore()

    api({ 'GET /community/member-invites': () => problem(500, 'INTERNAL_ERROR') })
    render('/admin/invites')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('makes a link: shows it once with a copy box, the QR code, a PNG download and a printable card', async () => {
    let sent: Record<string, unknown> | undefined
    const createUrl = vi.fn(() => 'blob:qr')
    URL.createObjectURL = createUrl
    URL.revokeObjectURL = vi.fn()
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined)
    api({
      'GET /community/settings': ok({
        name: 'Lotus Residents',
        logoUrl: 'https://files.example.test/logo.png',
        currency: 'INR',
        memberGroupLabel: 'Block',
      }),
      'GET /community/member-invites': ok(page([])),
      'POST /community/member-invites': (config) => {
        sent = body(config)
        return {
          status: 201,
          data: {
            invite: invite({ defaultGroup: 'Block C' }),
            link: 'https://app.example.test/join/tok123',
            qrCodePngBase64: QR,
          },
        }
      },
    })
    const user = userEvent.setup()
    render('/admin/invites')

    await user.click(await screen.findByRole('button', { name: 'Create invite link' }))
    const dialog = await screen.findByRole('dialog', { name: 'Create an invite link' })
    expect(within(dialog).getByLabelText(/^Valid for/)).toHaveValue('14')
    await user.type(within(dialog).getByLabelText(/^Put new members/), 'Block C')
    await user.click(within(dialog).getByRole('button', { name: 'Create link' }))

    await waitFor(() => {
      expect(sent).toEqual({ expiresInDays: 14, maxUses: 100, defaultGroup: 'Block C' })
    })
    const shown = await screen.findByRole('dialog', { name: 'Your invite link' })
    expect(within(shown).getByDisplayValue('https://app.example.test/join/tok123')).toHaveAttribute(
      'readonly',
    )
    const qr = within(shown).getByRole('img', { name: /QR code to register with Lotus Residents/ })
    expect(qr).toHaveAttribute('src', `data:image/png;base64,${QR}`)
    expect(within(shown).getByText(/Group: Block C/)).toBeInTheDocument()

    await user.click(within(shown).getByRole('button', { name: /Download QR code/ }))
    expect(createUrl).toHaveBeenCalled()
    await user.click(within(shown).getByRole('button', { name: 'Print the card' }))
    expect(print).toHaveBeenCalled()
    // Closing and opening again does not bring the link back.
    await user.click(within(shown).getByRole('button', { name: 'Done' }))
    await user.click(await screen.findByRole('button', { name: 'Create invite link' }))
    expect(await screen.findByRole('dialog', { name: 'Create an invite link' })).toBeInTheDocument()
    expect(
      screen.queryByDisplayValue('https://app.example.test/join/tok123'),
    ).not.toBeInTheDocument()
  })

  it('checks days and uses against the limits before sending', async () => {
    const post = vi.fn(() => ({ status: 201, data: {} }))
    api({ 'GET /community/member-invites': ok(page([])), 'POST /community/member-invites': post })
    const user = userEvent.setup()
    render('/admin/invites')

    await user.click(await screen.findByRole('button', { name: 'Create invite link' }))
    const dialog = await screen.findByRole('dialog', { name: 'Create an invite link' })
    await user.clear(within(dialog).getByLabelText(/^Valid for/))
    await user.type(within(dialog).getByLabelText(/^Valid for/), '91')
    await user.clear(within(dialog).getByLabelText(/^Most people/))
    await user.type(within(dialog).getByLabelText(/^Most people/), '0')
    await user.click(within(dialog).getByRole('button', { name: 'Create link' }))
    expect(await within(dialog).findByText('Days must be from 1 to 90')).toBeInTheDocument()
    expect(within(dialog).getByText('Uses must be from 1 to 1000')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('revokes a working link after asking, and offers nothing for the others', async () => {
    const calls: string[] = []
    api({
      'GET /community/member-invites': ok(
        page([invite(), invite({ id: 'i-2', state: 'EXPIRED' })]),
      ),
      'POST /community/member-invites/i-1/revoke': () => {
        calls.push('revoke')
        return { status: 200, data: invite({ state: 'REVOKED' }) }
      },
    })
    const user = userEvent.setup()
    render('/admin/invites')

    const buttons = await screen.findAllByRole('button', { name: /^Actions for / })
    await user.click(buttons[1]!) // the expired one
    expect(await screen.findByRole('menuitem', { name: 'Nothing to do' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    await user.keyboard('{Escape}')

    await user.click(buttons[0]!)
    await user.click(await screen.findByRole('menuitem', { name: 'Revoke' }))
    const confirm = await screen.findByRole('alertdialog')
    expect(calls).toEqual([])
    await user.click(within(confirm).getByRole('button', { name: 'Revoke' }))
    await waitFor(() => {
      expect(calls).toEqual(['revoke'])
    })
  })

  it('sends a personal invitation by email', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /community/member-invites': ok(page([])),
      'POST /community/member-invites/email': (config) => {
        sent = body(config)
        return { status: 201, data: { invite: invite({ invitedEmail: 'ravi@example.test' }) } }
      },
    })
    const user = userEvent.setup()
    render('/admin/invites')

    await user.click(await screen.findByRole('button', { name: 'Invite by email' }))
    const dialog = await screen.findByRole('dialog', { name: 'Invite someone by email' })
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))
    expect(await within(dialog).findByText('Enter a valid email address')).toBeInTheDocument()
    expect(sent).toBeUndefined()
    await user.type(within(dialog).getByLabelText(/^Email/), 'ravi@example.test')
    await user.type(within(dialog).getByLabelText('Their name'), 'Ravi Menon')
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))
    await waitFor(() => {
      expect(sent).toEqual({ email: 'ravi@example.test', expiresInDays: 14, name: 'Ravi Menon' })
    })
    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: 'Invite someone by email' }),
      ).not.toBeInTheDocument()
    })
  })

  it('switches creating off while suspended', async () => {
    api({
      'GET /community/plan': ok({
        planCode: 'S',
        planName: 'Starter',
        limits: {},
        features: {},
        usage: { members: 0, emailsThisMonth: 0 },
        communityStatus: 'SUSPENDED',
        subscription: null,
        expiringWindowDays: 30,
      }),
      'GET /community/member-invites': ok(page([])),
    })
    render('/admin/invites')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Create invite link' })).toBeDisabled()
    })
    expect(screen.getByRole('button', { name: 'Invite by email' })).toBeDisabled()
  })
})

// ---- registrations ----------------------------------------------------------------------------

describe('Registrations', () => {
  const queue = (rows: RegistrationView[]) => ({
    'GET /community/registrations': ok(page(rows)),
    'GET /community/members/counts': ok({
      total: 1,
      active: 1,
      inactive: 0,
      newThisMonth: 0,
      pendingRegistrations: rows.length,
    }),
  })

  it('shows who is waiting, with the count on the tab', async () => {
    api(
      queue([
        registration(),
        registration({ id: 'r-2', fullName: 'Ravi Menon', email: 'ravi@example.test' }),
      ]),
    )
    const { container } = render('/admin/invites?tab=registrations')

    expect(await screen.findByText('Priya Shah')).toBeInTheDocument()
    expect(screen.getByText('Ravi Menon')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Registrations.*2 waiting/ })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('has an empty state, and an error with a retry', async () => {
    api(queue([]))
    render('/admin/invites?tab=registrations')
    expect(await screen.findByText('Nobody is waiting')).toBeInTheDocument()
    cleanup()
    mock?.restore()

    api({ 'GET /community/registrations': () => problem(500, 'INTERNAL_ERROR') })
    render('/admin/invites?tab=registrations')
    expect(await screen.findByRole('button', { name: /try again|retry/i })).toBeInTheDocument()
  })

  it('approves one registration, which makes a member', async () => {
    let approved: Record<string, unknown> | undefined
    api({
      ...queue([registration()]),
      'POST /community/registrations/r-1/approve': (config) => {
        approved = body(config)
        return { status: 200, data: registration({ status: 'APPROVED', memberNo: 'M-0007' }) }
      },
    })
    const user = userEvent.setup()
    render('/admin/invites?tab=registrations')

    await user.click(await screen.findByRole('button', { name: 'Approve Priya Shah' }))
    await waitFor(() => {
      expect(approved).toEqual({})
    })
  })

  it('rejects only with a reason', async () => {
    let rejected: Record<string, unknown> | undefined
    api({
      ...queue([registration()]),
      'POST /community/registrations/r-1/reject': (config) => {
        rejected = body(config)
        return { status: 200, data: registration({ status: 'REJECTED' }) }
      },
    })
    const user = userEvent.setup()
    render('/admin/invites?tab=registrations')

    await user.click(await screen.findByRole('button', { name: 'Reject Priya Shah' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject Priya Shah?' })
    expect(within(dialog).getByRole('button', { name: 'Reject' })).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/^Reason/), 'Not a resident')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    await waitFor(() => {
      expect(rejected).toEqual({ reason: 'Not a resident' })
    })
  })

  it('asks before approving someone whose address another member already uses', async () => {
    let approved: Record<string, unknown> | undefined
    api({
      ...queue([registration({ emailUsedByMemberNo: 'M-0003' })]),
      'POST /community/registrations/r-1/approve': (config) => {
        approved = body(config)
        return { status: 200, data: registration({ status: 'APPROVED' }) }
      },
    })
    const user = userEvent.setup()
    render('/admin/invites?tab=registrations')

    expect(await screen.findByText(/already used by member M-0003/)).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Select Priya Shah' })).toBeDisabled() // not part of a bulk approval
    await user.click(screen.getByRole('button', { name: 'Approve Priya Shah' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(approved).toBeUndefined()
    await user.click(within(dialog).getByRole('button', { name: 'Approve anyway' }))
    await waitFor(() => {
      expect(approved).toEqual({ allowDuplicateEmail: true })
    })
  })

  it('approves several at once, and stops at the plan limit with an explanation', async () => {
    const approvedIds: string[] = []
    api({
      ...queue([
        registration({ id: 'r-1', fullName: 'Priya Shah' }),
        registration({ id: 'r-2', fullName: 'Ravi Menon', email: 'ravi@example.test' }),
        registration({ id: 'r-3', fullName: 'Sana Khan', email: 'sana@example.test' }),
      ]),
      'POST /community/registrations/r-1/approve': () => {
        approvedIds.push('r-1')
        return { status: 200, data: registration({ status: 'APPROVED' }) }
      },
      'POST /community/registrations/r-2/approve': () => {
        approvedIds.push('r-2')
        return problem(
          402,
          'PLAN_LIMIT_EXCEEDED',
          'The Starter plan allows up to 50 members and this community has 50.',
          { limit: 'max_members', limitValue: 50, current: 50, plan: 'STARTER' },
        )
      },
      'POST /community/registrations/r-3/approve': () => {
        approvedIds.push('r-3')
        return { status: 200, data: registration({ status: 'APPROVED' }) }
      },
    })
    const user = userEvent.setup()
    render('/admin/invites?tab=registrations')

    await user.click(
      await screen.findByRole('checkbox', { name: 'Select all that can be approved' }),
    )
    await user.click(screen.getByRole('button', { name: 'Approve 3 selected' }))
    const confirm = await screen.findByRole('alertdialog')
    await user.click(within(confirm).getByRole('button', { name: 'Approve' }))

    expect(
      await screen.findByRole('dialog', { name: 'Your plan has no room for more members' }),
    ).toBeInTheDocument()
    // The second hit the limit, so the third was not even tried.
    expect(approvedIds).toEqual(['r-1', 'r-2'])
  })
})

// ---- the public join page ---------------------------------------------------------------------

const joinView = (over: Partial<PublicInviteView> = {}): PublicInviteView => ({
  communityName: 'Lotus Residents',
  logoUrl: 'https://files.example.test/logo.png',
  groupLabel: 'Block',
  defaultGroup: null,
  fields: [
    { name: 'fullName', label: 'Full name', type: 'text', required: true, maxLength: 150 },
    { name: 'email', label: 'Email', type: 'email', required: true, maxLength: 254 },
    { name: 'phone', label: 'Phone', type: 'tel', required: false, maxLength: 30 },
    { name: 'group', label: 'Group', type: 'text', required: false, maxLength: 100 },
  ],
  ...over,
})

describe('Join page', () => {
  beforeEach(() => {
    setSession(null)
  })

  it('shows the community, asks for the details, and has a consent checkbox that starts unticked; passes axe', async () => {
    api({ 'GET /public/invites/abc123': ok(joinView()) })
    const { container } = render('/join/abc123')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Join Lotus Residents' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Lotus Residents', { selector: 'p' })).toBeInTheDocument()
    expect(screen.getByLabelText(/^Full name/)).toBeInTheDocument()
    expect(screen.getByLabelText('Block')).toBeInTheDocument() // the community's own word for the group
    expect(screen.getByRole('checkbox', { name: /may email me about dues/ })).not.toBeChecked()
    expect(screen.queryByRole('link', { name: 'Log in' })).not.toBeInTheDocument() // members have no login
    await expectNoA11yViolations(container)
  })

  it('checks the form, then sends the registration and says thank you', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /public/invites/abc123': ok(joinView()),
      'POST /public/invites/abc123/register': (config) => {
        sent = body(config)
        return { status: 200, data: { received: true } }
      },
    })
    const user = userEvent.setup()
    const { container } = render('/join/abc123')

    await user.click(await screen.findByRole('button', { name: 'Send my registration' }))
    expect(await screen.findByText('Enter your full name')).toBeInTheDocument()
    expect(screen.getByText('Enter your email')).toBeInTheDocument()
    expect(sent).toBeUndefined()

    await user.type(screen.getByLabelText(/^Full name/), 'Priya Shah')
    await user.type(screen.getByLabelText(/^Email/), 'priya@example.test')
    await user.click(screen.getByRole('checkbox', { name: /may email me about dues/ }))
    await user.click(screen.getByRole('button', { name: 'Send my registration' }))

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Thank you, your registration was received',
      }),
    ).toBeInTheDocument()
    expect(sent).toEqual({
      fullName: 'Priya Shah',
      email: 'priya@example.test',
      consentEmail: true,
    })
    await expectNoA11yViolations(container)
  })

  it('does not ask for the group when the link already decided it, and sends it', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /public/invites/abc123': ok(joinView({ defaultGroup: 'Block C' })),
      'POST /public/invites/abc123/register': (config) => {
        sent = body(config)
        return { status: 200, data: { received: true } }
      },
    })
    const user = userEvent.setup()
    render('/join/abc123')

    await user.type(await screen.findByLabelText(/^Full name/), 'Priya Shah')
    await user.type(screen.getByLabelText(/^Email/), 'priya@example.test')
    expect(screen.queryByLabelText('Block')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send my registration' }))
    await waitFor(() => {
      expect(sent).toMatchObject({ group: 'Block C', consentEmail: false })
    })
  })

  it('shows the server’s own message on the field it names, and a plain one for too many tries', async () => {
    let calls = 0
    api({
      'GET /public/invites/abc123': ok(joinView()),
      'POST /public/invites/abc123/register': () => {
        calls += 1
        return calls === 1
          ? problem(400, 'VALIDATION_FAILED', 'Invalid', {
              errors: [{ field: 'phone', message: 'is not a valid phone number' }],
            })
          : problem(429, 'RATE_LIMITED')
      },
    })
    const user = userEvent.setup()
    render('/join/abc123')

    await user.type(await screen.findByLabelText(/^Full name/), 'Priya Shah')
    await user.type(screen.getByLabelText(/^Email/), 'priya@example.test')
    await user.click(screen.getByRole('button', { name: 'Send my registration' }))
    expect(await screen.findByText('is not a valid phone number')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send my registration' }))
    expect(await screen.findByText(/Too many tries from this device/)).toBeInTheDocument()
  })

  it('gives one plain message for a link that is unknown, expired, used up or withdrawn', async () => {
    api({ 'GET /public/invites/abc123': () => problem(404, 'INVITE_UNAVAILABLE') })
    const { container } = render('/join/abc123')
    expect(
      await screen.findByRole('heading', { level: 1, name: 'This invitation cannot be used' }),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText(/^Full name/)).not.toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('has a hidden field that people never see, and does not send it when empty', async () => {
    let sent: Record<string, unknown> | undefined
    api({
      'GET /public/invites/abc123': ok(joinView()),
      'POST /public/invites/abc123/register': (config) => {
        sent = body(config)
        return { status: 200, data: { received: true } }
      },
    })
    const user = userEvent.setup()
    const { container } = render('/join/abc123')
    await user.type(await screen.findByLabelText(/^Full name/), 'Priya Shah')
    await user.type(screen.getByLabelText(/^Email/), 'priya@example.test')
    const trap = container.querySelector<HTMLInputElement>('input[name="website"]')
    expect(trap).not.toBeNull()
    expect(trap?.closest('[aria-hidden="true"]')).not.toBeNull()
    expect(trap).toHaveAttribute('tabindex', '-1')
    await user.click(screen.getByRole('button', { name: 'Send my registration' }))
    await waitFor(() => {
      expect(sent).toBeDefined()
    })
    expect(sent).not.toHaveProperty('website')
  })
})

// ---- the public payment page ------------------------------------------------------------------

const payView = (over: Partial<PublicPayView> = {}): PublicPayView => ({
  communityName: 'Lotus Residents',
  logoUrl: null,
  invoiceNo: 'INV-0001',
  description: 'Maintenance',
  period: '2026-10',
  currency: 'INR',
  amount: '2500.00',
  amountPaid: '500.00',
  balanceDue: '2000.00',
  dueDate: '2026-10-25',
  status: 'PARTIAL',
  payable: true,
  upi: {
    payeeName: 'Lotus Residents Welfare',
    link: 'upi://pay?pa=lotus@bank&am=2000.00',
    qrCodePngBase64: QR,
  },
  manualConfirmation: true,
  notice: 'The committee marks it paid after checking the bank statement.',
  ...over,
})

describe('Pay page', () => {
  beforeEach(() => {
    setSession(null)
  })

  it('shows the amount due, the UPI QR code, an open-in-app button, and that the community confirms the payment; passes axe', async () => {
    api({ 'GET /public/pay/abc123': ok(payView()) })
    const { container } = render('/pay/abc123')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Payment details' }),
    ).toBeInTheDocument()
    expect(screen.getByText('INV-0001')).toBeInTheDocument()
    expect(screen.getByText('₹2,000.00')).toBeInTheDocument() // the amount due, not the total
    expect(screen.getByText('₹500.00')).toBeInTheDocument() // already paid
    expect(
      screen.getByRole('img', { name: /UPI QR code to pay ₹2,000\.00 to Lotus Residents Welfare/ }),
    ).toHaveAttribute('src', `data:image/png;base64,${QR}`)
    expect(screen.getByRole('link', { name: 'Open in my UPI app' })).toHaveAttribute(
      'href',
      'upi://pay?pa=lotus@bank&am=2000.00',
    )
    const note = screen.getByRole('note')
    expect(note).toHaveTextContent('Your payment is confirmed by the community.')
    expect(note).toHaveTextContent('does not update your account at once')
    expect(note).toHaveTextContent('The committee marks it paid after checking the bank statement.')
    await expectNoA11yViolations(container)
  })

  it('never turns a link that is not a UPI link into a button', async () => {
    api({
      'GET /public/pay/abc123': ok(
        payView({
          upi: { payeeName: 'X', link: 'https://evil.example.test/steal', qrCodePngBase64: QR },
        }),
      ),
    })
    render('/pay/abc123')
    await screen.findByRole('heading', { level: 1, name: 'Payment details' })
    expect(screen.queryByRole('link', { name: 'Open in my UPI app' })).not.toBeInTheDocument()
  })

  it('says there is nothing to pay for a bill that is settled, and shows no QR code', async () => {
    api({
      'GET /public/pay/abc123': ok(
        payView({
          status: 'PAID',
          payable: false,
          amountPaid: '2500.00',
          balanceDue: '0.00',
          upi: undefined,
        }),
      ),
    })
    render('/pay/abc123')
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Nothing to pay' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: /UPI QR/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  it('gives one plain message for any link that cannot be used', async () => {
    api({ 'GET /public/pay/abc123': () => problem(404, 'PAY_LINK_UNAVAILABLE') })
    const { container } = render('/pay/abc123')
    expect(
      await screen.findByRole('heading', { level: 1, name: 'This payment link cannot be used' }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })
})
