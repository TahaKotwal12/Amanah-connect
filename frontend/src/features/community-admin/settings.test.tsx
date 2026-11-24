import { cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { createQueryClient } from '@/lib/queryClient'
import { emptyCommunityApi } from '@/test/communityApi'
import { expectNoA11yViolations } from '@/test/a11y'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { notify } from '@/lib/notify'
import { renderApp } from '@/test/renderApp'
import type { SettingsView } from '@/types'

configure({ asyncUtilTimeout: 15_000 })
vi.setConfig({ testTimeout: 30_000 })

const ok =
  (data: unknown, status = 200): MockHandler =>
  () => ({ status, data })
const body = (config: InternalAxiosRequestConfig) =>
  JSON.parse(config.data as string) as Record<string, unknown>

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
beforeEach(() => setSession('COMMUNITY_ADMIN'))
afterEach(() => {
  cleanup()
  mock?.restore()
  mock = undefined
  resetSession()
})

const settings = (over: Partial<SettingsView> = {}): SettingsView => ({
  name: 'Lotus Residents',
  contactName: 'Asha Rao',
  contactEmail: 'office@lotus.example',
  contactPhone: '+91 98765 43210',
  addressLine1: '12 Lotus Lane',
  addressLine2: null,
  city: 'Bengaluru',
  state: 'Karnataka',
  postalCode: '560038',
  country: 'IN',
  dateOfEstablishment: null,
  logoKey: null,
  logoUrl: null,
  currency: 'INR',
  financialYearStartMonth: 4,
  financialSettingsLocked: false,
  upiId: null,
  upiPayeeName: null,
  memberGroupLabel: 'Flat',
  openingBalance: '0.00',
  notifications: {
    dueReminderDaysBefore: 3,
    overdueReminderEveryDays: 7,
    sendWelcomeEmail: true,
    sendReceiptEmail: true,
  },
  version: 4,
  ...over,
})

// ---- the page ---------------------------------------------------------------------------------

describe('settings page', () => {
  it('has the five sections, and no UPI payments tab in phase 1', async () => {
    api({ 'GET /community/settings': ok(settings()) })
    render('/admin/settings')
    const tabs = await screen.findByRole('tablist', { name: 'Settings sections' })
    expect(
      within(tabs)
        .getAllByRole('tab')
        .map((t) => t.textContent),
    ).toEqual(['Community profile', 'Notifications', 'Security', 'Plan and usage', 'Data'])
    expect(within(tabs).queryByRole('tab', { name: 'Payments' })).not.toBeInTheDocument()
  })
})

// ---- profile ----------------------------------------------------------------------------------

describe('community profile', () => {
  it('shows what is saved, sends only what changed with the version, and passes axe', async () => {
    const save = vi.fn<MockHandler>(() => ({
      status: 200,
      data: settings({ name: 'Lotus Welfare', version: 5 }),
    }))
    api({ 'GET /community/settings': ok(settings()), 'PATCH /community/settings': save })
    const user = userEvent.setup()
    const { container } = render('/admin/settings')

    const name = await screen.findByLabelText(/^Name/)
    expect(name).toHaveValue('Lotus Residents')
    expect(screen.getByLabelText(/Contact email/)).toHaveValue('office@lotus.example')
    await expectNoA11yViolations(container)

    await user.clear(name)
    await user.type(name, 'Lotus Welfare')
    await user.click(screen.getByRole('button', { name: 'Save the profile' }))

    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
    expect(body(save.mock.calls[0]![0])).toEqual({ name: 'Lotus Welfare', version: 4 })
  })

  it('does not call the server when nothing changed', async () => {
    const info = vi.spyOn(notify, 'info').mockImplementation(() => undefined)
    const save = vi.fn<MockHandler>(() => ({ status: 200, data: settings() }))
    api({ 'GET /community/settings': ok(settings()), 'PATCH /community/settings': save })
    const user = userEvent.setup()
    render('/admin/settings')
    await screen.findByLabelText(/^Name/)
    await user.click(screen.getByRole('button', { name: 'Save the profile' }))
    await waitFor(() => expect(info).toHaveBeenCalledWith(expect.stringMatching(/Nothing to save/)))
    expect(save).not.toHaveBeenCalled()
    info.mockRestore()
  })

  it('explains a bad value before anything is sent', async () => {
    const save = vi.fn<MockHandler>(() => ({ status: 200, data: settings() }))
    api({ 'GET /community/settings': ok(settings()), 'PATCH /community/settings': save })
    const user = userEvent.setup()
    render('/admin/settings')
    const name = await screen.findByLabelText(/^Name/)

    await user.clear(name)
    await user.tab()
    expect(await screen.findByText('Enter the community name')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save the profile' }))
    expect(save).not.toHaveBeenCalled()

    await user.type(name, 'Lotus')
    const email = screen.getByLabelText(/Contact email/)
    await user.clear(email)
    await user.type(email, 'nope')
    await user.tab()
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument()
  })

  it('locks the currency and the financial year once money has moved', async () => {
    api({ 'GET /community/settings': ok(settings({ financialSettingsLocked: true })) })
    render('/admin/settings')
    expect(await screen.findByLabelText(/^Currency/)).toBeDisabled()
    expect(screen.getByRole('note')).toHaveTextContent(/cannot change any more/)
  })

  it('shows the logo when there is one, and a way to remove it', async () => {
    api({
      'GET /community/settings': ok(
        settings({ logoKey: 'communities/c1/logo/l.png', logoUrl: 'https://files.example/l.png' }),
      ),
    })
    render('/admin/settings')
    expect(await screen.findByRole('img', { name: 'Lotus Residents logo' })).toHaveAttribute(
      'src',
      'https://files.example/l.png',
    )
    expect(screen.getByRole('button', { name: 'Remove the logo' })).toBeInTheDocument()
  })
})

// ---- notifications ----------------------------------------------------------------------------

describe('notifications', () => {
  it('saves the reminder days and the email switches as numbers and booleans', async () => {
    const save = vi.fn<MockHandler>(() => ({ status: 200, data: settings({ version: 5 }) }))
    api({ 'GET /community/settings': ok(settings()), 'PATCH /community/settings': save })
    const user = userEvent.setup()
    const { container } = render('/admin/settings?tab=notifications')

    const before = await screen.findByLabelText(/Remind before the due date/)
    expect(before).toHaveValue('3')
    await expectNoA11yViolations(container)
    await user.clear(before)
    await user.type(before, '5')
    await user.click(screen.getByRole('checkbox', { name: /Receipt email/ }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
    expect(body(save.mock.calls[0]![0])).toEqual({
      version: 4,
      notifications: {
        dueReminderDaysBefore: 5,
        overdueReminderEveryDays: 7,
        sendWelcomeEmail: true,
        sendReceiptEmail: false,
      },
    })
  })

  it('refuses days that are out of range', async () => {
    const save = vi.fn<MockHandler>(() => ({ status: 200, data: settings() }))
    api({ 'GET /community/settings': ok(settings()), 'PATCH /community/settings': save })
    const user = userEvent.setup()
    render('/admin/settings?tab=notifications')
    const every = await screen.findByLabelText(/Repeat when overdue/)
    await user.clear(every)
    await user.type(every, '0')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Between 1 and 90 days')).toBeInTheDocument()
    expect(save).not.toHaveBeenCalled()
  })
})

// ---- security ---------------------------------------------------------------------------------

describe('security', () => {
  it('changes the password only when it is acceptable and typed twice', async () => {
    const change = vi.fn<MockHandler>(() => ({ status: 204, data: undefined }))
    api({ 'GET /community/settings': ok(settings()), 'POST /auth/password/change': change })
    const user = userEvent.setup()
    const { container } = render('/admin/settings?tab=security')

    const submit = await screen.findByRole('button', { name: 'Change the password' })
    expect(submit).toBeDisabled()
    await expectNoA11yViolations(container)

    await user.type(screen.getByLabelText('Current password'), 'old-password-1')
    await user.type(screen.getByLabelText('New password'), 'short')
    expect(submit).toBeDisabled()
    await user.clear(screen.getByLabelText('New password'))
    await user.type(screen.getByLabelText('New password'), 'Tulip-Harbour-92!')
    await user.type(screen.getByLabelText('Type the new password again'), 'Tulip-Harbour-93!')
    expect(await screen.findByText('The two passwords are not the same.')).toBeInTheDocument()
    expect(submit).toBeDisabled()

    await user.clear(screen.getByLabelText('Type the new password again'))
    await user.type(screen.getByLabelText('Type the new password again'), 'Tulip-Harbour-92!')
    expect(submit).toBeEnabled()
    await user.click(submit)

    await waitFor(() => expect(change).toHaveBeenCalledTimes(1))
    expect(body(change.mock.calls[0]![0])).toEqual({
      currentPassword: 'old-password-1',
      newPassword: 'Tulip-Harbour-92!',
    })
    expect(screen.getByLabelText('Current password')).toHaveValue('')
  })

  it('shows the server refusing the current password', async () => {
    api({
      'GET /community/settings': ok(settings()),
      'POST /auth/password/change': () => problem(403, 'INVALID_CURRENT_PASSWORD'),
    })
    const user = userEvent.setup()
    render('/admin/settings?tab=security')
    await user.type(await screen.findByLabelText('Current password'), 'wrong-password-1')
    await user.type(screen.getByLabelText('New password'), 'Tulip-Harbour-92!')
    await user.type(screen.getByLabelText('Type the new password again'), 'Tulip-Harbour-92!')
    await user.click(screen.getByRole('button', { name: 'Change the password' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/current password/i)
  })

  it('turns two-step verification on, then shows the recovery codes once', async () => {
    setSession('COMMUNITY_ADMIN', { totpEnabled: false })
    const enable = vi.fn<MockHandler>(() => ({
      status: 200,
      data: { recoveryCodes: ['AAAA-1111', 'BBBB-2222'] },
    }))
    api({
      'GET /community/settings': ok(settings()),
      'POST /auth/2fa/setup': ok({
        secret: 'JBSWY3DPEHPK3PXP',
        otpauthUri: 'otpauth://totp/x?secret=JBSWY3DPEHPK3PXP',
      }),
      'POST /auth/2fa/enable': enable,
      'GET /auth/me': ok({
        user: {
          id: 'u',
          email: 'admin@example.test',
          fullName: 'Community Admin',
          role: 'COMMUNITY_ADMIN',
          status: 'ACTIVE',
        },
        community: { id: 'c', name: 'Test Community', slug: 't', status: 'ACTIVE' },
        flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: false },
      }),
    })
    const user = userEvent.setup()
    render('/admin/settings?tab=security')

    expect(await screen.findByText('Off')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Turn on' }))
    const dialog = await screen.findByRole('dialog', { name: 'Turn on two-step verification' })
    await user.click(within(dialog).getByRole('button', { name: 'Start' }))
    expect(await within(dialog).findByRole('img', { name: /QR code/ })).toBeInTheDocument()

    const group = within(dialog).getByRole('group', { name: 'Authenticator code' })
    const boxes = within(group).getAllByRole('textbox')
    await user.click(boxes[0]!)
    await user.keyboard('123456')
    await user.click(within(dialog).getByRole('button', { name: 'Turn on' }))

    await waitFor(() => expect(enable).toHaveBeenCalledTimes(1))
    expect(body(enable.mock.calls[0]![0])).toEqual({ code: '123456' })
    const codes = await within(dialog).findByRole('list', { name: 'Recovery codes' })
    expect(codes).toHaveTextContent('AAAA-1111')
    const done = within(dialog).getByRole('button', { name: 'Done' })
    expect(done).toBeDisabled() // not until the codes are confirmed saved
    await user.click(within(dialog).getByRole('checkbox', { name: /saved these codes/ }))
    expect(done).toBeEnabled()
  })

  it('will not offer to turn it off when the platform requires it', async () => {
    setSession('COMMUNITY_ADMIN', { totpEnabled: true, mfaMandatory: true })
    api({ 'GET /community/settings': ok(settings()) })
    render('/admin/settings?tab=security')
    expect(await screen.findByText(/platform requires two-step verification/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Turn off' })).not.toBeInTheDocument()
  })

  it('turns it off with the password and a recovery code', async () => {
    setSession('COMMUNITY_ADMIN', { totpEnabled: true, mfaMandatory: false })
    const off = vi.fn<MockHandler>(() => ({ status: 204, data: undefined }))
    api({
      'GET /community/settings': ok(settings()),
      'POST /auth/2fa/disable': off,
      'GET /auth/me': ok({
        user: {
          id: 'u',
          email: 'admin@example.test',
          fullName: 'Community Admin',
          role: 'COMMUNITY_ADMIN',
          status: 'ACTIVE',
        },
        community: { id: 'c', name: 'Test Community', slug: 't', status: 'ACTIVE' },
        flags: { totpEnabled: false, mfaSetupRequired: false, mfaMandatory: false },
      }),
    })
    const user = userEvent.setup()
    render('/admin/settings?tab=security')
    await user.click(await screen.findByRole('button', { name: 'Turn off' }))
    const dialog = await screen.findByRole('dialog', { name: 'Turn off two-step verification?' })
    const confirm = within(dialog).getByRole('button', { name: 'Turn off' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Password'), 'my-password-1')
    await user.click(within(dialog).getByRole('button', { name: 'Use a recovery code instead' }))
    await user.type(within(dialog).getByLabelText('Recovery code'), 'AAAA-1111')
    await user.click(confirm)
    await waitFor(() => expect(off).toHaveBeenCalledTimes(1))
    expect(body(off.mock.calls[0]![0])).toEqual({
      password: 'my-password-1',
      recoveryCode: 'AAAA-1111',
    })
  })
})

// ---- plan and usage ---------------------------------------------------------------------------

describe('plan and usage', () => {
  const plan = (over: Record<string, unknown> = {}) => ({
    planCode: 'STARTER',
    planName: 'Starter',
    limits: { max_members: 50, emails_per_month: 1000 },
    features: { csv_export: true, pdf_reports: false },
    usage: { members: 47, emailsThisMonth: 120 },
    communityStatus: 'ACTIVE',
    subscription: {
      status: 'EXPIRING',
      periodStart: '2026-01-01',
      periodEnd: '2026-12-31',
      daysLeft: 12,
    },
    expiringWindowDays: 30,
    ...over,
  })

  it('shows the plan, the paid period, the meters and what is included', async () => {
    api({ 'GET /community/plan': ok(plan()) })
    const { container } = render('/admin/settings?tab=plan')
    expect(await screen.findByRole('heading', { name: 'Starter plan' })).toBeInTheDocument()
    expect(screen.getByText(/12 days left/)).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Members used' })).toHaveAttribute(
      'aria-valuenow',
      '47',
    )
    expect(screen.getByText(/47 of 50 members/)).toBeInTheDocument()
    expect(screen.getByText(/120 of 1,000 emails/)).toBeInTheDocument()
    expect(screen.getByText(/Export to CSV/)).toHaveTextContent('included')
    expect(screen.getByText(/PDF reports/)).toHaveTextContent('not included')
    expect(screen.getByText(/contact us from the Support page/)).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('says so when no limit applies or nothing was ever paid', async () => {
    api({ 'GET /community/plan': ok(plan({ limits: {}, subscription: null })) })
    render('/admin/settings?tab=plan')
    expect(await screen.findByText(/No paid period has been recorded yet/)).toBeInTheDocument()
    expect(screen.getByText(/47 members, no limit/)).toBeInTheDocument()
  })
})

// ---- data -------------------------------------------------------------------------------------

describe('data', () => {
  const exportRow = (over: Record<string, unknown> = {}) => ({
    id: 'x1',
    status: 'READY',
    requestedAt: '2026-10-10T10:00:00Z',
    completedAt: '2026-10-10T10:02:00Z',
    sizeBytes: 2_500_000,
    tables: { members: 40 },
    linkExpiresAt: '2026-10-12T10:00:00Z',
    downloadCount: 0,
    error: null,
    ...over,
  })

  it('asks for an export, and offers a download for one that is ready', async () => {
    let rows = [exportRow()]
    const create = vi.fn<MockHandler>(() => {
      rows = [
        exportRow({ id: 'x2', status: 'PENDING', sizeBytes: null, completedAt: null }),
        ...rows,
      ]
      return { status: 202, data: rows[0] }
    })
    api({
      'GET /community/settings': ok(settings()),
      'GET /community/data-exports': () => ({ status: 200, data: rows }),
      'POST /community/data-exports': create,
    })
    const user = userEvent.setup()
    const { container } = render('/admin/settings?tab=data')

    const list = await screen.findByRole('list', { name: 'Your downloads' })
    expect(within(list).getByRole('button', { name: 'Download' })).toBeInTheDocument()
    expect(within(list).getByText('Ready')).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'Prepare a download' }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(await screen.findByRole('button', { name: 'Being prepared' })).toBeDisabled()
  })

  it('erases a member only after their number is typed and a reason given', async () => {
    const erase = vi.fn<MockHandler>(() => ({
      status: 200,
      data: {
        memberId: 'm1',
        memberNo: 'M-001',
        anonymisedAt: '2026-10-10T10:00:00Z',
        kept: { invoices: 3, payments: 2, receipts: 2, unpaidInvoices: 1, outstanding: '500.00' },
        removed: {
          registrations: 1,
          complaints: 1,
          complaintComments: 2,
          emails: 6,
          receiptPdfs: 2,
        },
      },
    }))
    api({
      'GET /community/settings': ok(settings()),
      'GET /community/data-exports': ok([]),
      'GET /community/members': ok({
        items: [
          {
            id: 'm1',
            memberNo: 'M-001',
            fullName: 'Asha Rao',
            email: null,
            phone: null,
            group: null,
            status: 'ACTIVE',
            statusReason: null,
            statusChangedAt: null,
            joinedOn: null,
            customFields: null,
            consentEmail: true,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        ],
        total: 1,
        page: 0,
        size: 100,
      }),
      'GET /community/members/m1': ok({
        id: 'm1',
        memberNo: 'M-001',
        fullName: 'Asha Rao',
        email: null,
        phone: null,
        group: null,
        status: 'ACTIVE',
        statusReason: null,
        statusChangedAt: null,
        joinedOn: null,
        customFields: null,
        consentEmail: true,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-01T00:00:00Z',
      }),
      'POST /community/members/m1/anonymise': erase,
    })
    const user = userEvent.setup()
    render('/admin/settings?tab=data')
    await user.click(await screen.findByRole('button', { name: "Erase a member's data" }))
    const dialog = await screen.findByRole('dialog', { name: "Erase a member's personal data" })
    const submit = within(dialog).getByRole('button', { name: 'Erase for good' })
    expect(submit).toBeDisabled()

    await user.click(within(dialog).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name: /Asha Rao/ }))
    const typed = await within(dialog).findByLabelText(/to confirm/)
    await user.type(typed, 'M-002')
    await user.type(within(dialog).getByLabelText('Why'), 'Asked to be forgotten')
    expect(submit).toBeDisabled() // the wrong number
    await user.clear(typed)
    await user.type(typed, 'M-001')
    expect(submit).toBeEnabled()
    await user.click(submit)

    await waitFor(() => expect(erase).toHaveBeenCalledTimes(1))
    expect(body(erase.mock.calls[0]![0])).toEqual({
      memberNo: 'M-001',
      reason: 'Asked to be forgotten',
    })
    expect(await screen.findByRole('status')).toHaveTextContent(/Member M-001 was erased/)
    expect(screen.getByRole('status')).toHaveTextContent(/3 bills, 2 payments, 2 receipts/)
  })
})
