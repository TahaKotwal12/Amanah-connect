import { cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { createQueryClient } from '@/lib/queryClient'
import { emptyCommunityApi } from '@/test/communityApi'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, problem, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import type { FeePlanView, GeneratePreview, InvoiceDetail, InvoiceView, PaymentView } from '@/types'

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

// ---- fixtures ---------------------------------------------------------------------------------

const feePlan: FeePlanView = {
  id: 'fp1',
  name: 'Monthly maintenance',
  kind: 'MAINTENANCE',
  amount: '1500.00',
  frequency: 'MONTHLY',
  dueDay: 10,
  appliesTo: 'ALL_ACTIVE',
  group: null,
  memberIds: [],
  active: true,
  autoGenerate: false,
  lastGeneratedPeriod: null,
  currentPeriod: '2026-10',
  createdAt: '2026-01-01T00:00:00Z',
}

const invoice = (over: Partial<InvoiceView> = {}): InvoiceView => ({
  id: 'inv1',
  invoiceNo: 'INV-0001',
  status: 'ISSUED',
  kind: 'MAINTENANCE',
  memberId: 'm1',
  memberNo: 'M-001',
  memberName: 'Asha Rao',
  feePlanId: 'fp1',
  period: '2026-10',
  description: 'Monthly maintenance',
  amount: '1500.00',
  amountPaid: '0.00',
  balance: '1500.00',
  issuedOn: '2026-10-01',
  dueDate: '2026-10-10',
  cancelReason: null,
  cancelledAt: null,
  version: 0,
  createdAt: '2026-10-01T00:00:00Z',
  ...over,
})

const payment = (over: Partial<PaymentView> = {}): PaymentView => ({
  id: 'pay1',
  kind: 'PAYMENT',
  invoiceId: 'inv1',
  invoiceNo: 'INV-0001',
  memberId: 'm1',
  memberNo: 'M-001',
  payerName: 'Asha Rao',
  amount: '500.00',
  method: 'UPI',
  reference: null,
  receivedOn: '2026-10-05',
  reversedOf: null,
  reversalReason: null,
  reversed: false,
  receiptId: 'rc1',
  receiptNo: 'RCT-0001',
  createdAt: '2026-10-05T10:00:00Z',
  ...over,
})

const detail = (over: Partial<InvoiceDetail> = {}): InvoiceDetail => ({
  invoice: invoice(),
  payments: [],
  reminders: [],
  ...over,
})

// ---- generate invoices ------------------------------------------------------------------------

describe('generate invoices wizard', () => {
  const preview = (over: Partial<GeneratePreview> = {}): GeneratePreview => ({
    feePlanId: 'fp1',
    feePlanName: 'Monthly maintenance',
    period: '2026-10',
    dueDate: '2026-10-10',
    eligibleMembers: 37,
    willCreate: 32,
    alreadyBilled: 5,
    skippedInactive: 0,
    amountEach: '1500.00',
    totalToBill: '48000.00',
    ...over,
  })

  async function openWizard() {
    const user = userEvent.setup()
    render('/admin/billing')
    await user.click(await screen.findByRole('button', { name: 'Generate invoices' }))
    const dialog = await screen.findByRole('dialog', { name: 'Generate invoices' })
    await user.click(within(dialog).getByRole('combobox', { name: 'Fee plan' }))
    await user.click(await screen.findByRole('option', { name: /Monthly maintenance/ }))
    return { user, dialog }
  }

  it('previews the count and total, then reports what was created and what already existed', async () => {
    const run = vi.fn<MockHandler>(() => ({
      status: 200,
      data: {
        feePlanId: 'fp1',
        period: '2026-10',
        dueDate: '2026-10-10',
        eligibleMembers: 37,
        created: 32,
        alreadyBilled: 5,
        skippedInactive: 0,
        emailsQueued: 0,
        emailsSkippedNoAddress: 0,
        emailsSkippedNoConsent: 0,
        emailsSkippedQuota: 0,
        firstInvoiceNo: 'INV-0006',
        lastInvoiceNo: 'INV-0037',
      },
    }))
    const calls = api({
      'GET /community/fee-plans': ok([feePlan]),
      'POST /community/invoices/generate/preview': ok(preview()),
      'POST /community/invoices/generate': run,
    })
    const { user, dialog } = await openWizard()

    await user.click(within(dialog).getByRole('button', { name: 'See what would happen' }))
    expect(await within(dialog).findByText('Nothing has been created yet.')).toBeInTheDocument()
    expect(within(dialog).getByText('₹48,000.00')).toBeInTheDocument()
    expect(within(dialog).getByText('32')).toBeInTheDocument()
    // Previewing writes nothing.
    expect(calls.count('POST /community/invoices/generate')).toBe(0)

    await user.click(within(dialog).getByRole('button', { name: 'Create 32 invoices' }))
    expect(await within(dialog).findByText('32 created, 5 already existed')).toBeInTheDocument()
    expect(run).toHaveBeenCalledTimes(1)
    expect(body(run.mock.calls[0]![0])).toMatchObject({ feePlanId: 'fp1' })
  })

  it('will not create anything when everyone is already billed', async () => {
    api({
      'GET /community/fee-plans': ok([feePlan]),
      'POST /community/invoices/generate/preview': ok(
        preview({ willCreate: 0, alreadyBilled: 37, totalToBill: '0.00' }),
      ),
    })
    const { user, dialog } = await openWizard()
    await user.click(within(dialog).getByRole('button', { name: 'See what would happen' }))
    expect(
      await within(dialog).findByText(/Everyone is already billed for this period/),
    ).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /^Create 0 invoices$/ })).toBeDisabled()
  })

  it('asks for a plan before it previews', async () => {
    api({ 'GET /community/fee-plans': ok([feePlan]) })
    const user = userEvent.setup()
    render('/admin/billing')
    await user.click(await screen.findByRole('button', { name: 'Generate invoices' }))
    const dialog = await screen.findByRole('dialog', { name: 'Generate invoices' })
    await user.click(within(dialog).getByRole('button', { name: 'See what would happen' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Choose a fee plan')
  })
})

// ---- record a payment -------------------------------------------------------------------------

describe('record payment', () => {
  function paymentApi(extra: Record<string, MockHandler> = {}) {
    return api({
      'GET /community/invoices/inv1': ok(detail()),
      ...extra,
    })
  }

  async function openForm() {
    const user = userEvent.setup()
    render('/admin/billing/invoices/inv1')
    await user.click(await screen.findByRole('button', { name: 'Record payment' }))
    const sheet = await screen.findByRole('dialog', { name: 'Record a payment' })
    return { user, sheet }
  }

  it('prefills the amount with the balance', async () => {
    paymentApi()
    const { sheet } = await openForm()
    expect(within(sheet).getByLabelText(/Amount received/)).toHaveValue('1500.00')
  })

  it('refuses an overpayment before anything is sent', async () => {
    const calls = paymentApi()
    const { user, sheet } = await openForm()
    const amount = within(sheet).getByLabelText(/Amount received/)
    await user.clear(amount)
    await user.type(amount, '1500.01')
    await user.click(within(sheet).getByRole('button', { name: 'Record payment' }))
    expect(
      await within(sheet).findByText(
        'That is more than the balance of ₹1,500.00. Enter the balance or less.',
      ),
    ).toBeInTheDocument()
    expect(calls.count('POST /community/invoices/inv1/payments')).toBe(0)
  })

  it('sends a partial payment with an Idempotency-Key and shows the receipt', async () => {
    const record = vi.fn<MockHandler>(() => ({
      status: 201,
      data: {
        payment: payment(),
        invoice: invoice({
          status: 'PARTIAL',
          amountPaid: '500.00',
          balance: '1000.00',
          version: 1,
        }),
        replayed: false,
      },
    }))
    paymentApi({ 'POST /community/invoices/inv1/payments': record })
    const { user, sheet } = await openForm()
    const amount = within(sheet).getByLabelText(/Amount received/)
    await user.clear(amount)
    await user.type(amount, '500')
    await user.click(within(sheet).getByRole('button', { name: 'Record payment' }))

    expect(await within(sheet).findByText('Payment recorded')).toBeInTheDocument()
    expect(within(sheet).getByText('RCT-0001')).toBeInTheDocument()
    expect(within(sheet).getByText('₹1,000.00')).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /Download receipt/ })).toBeEnabled()
    expect(within(sheet).getByRole('button', { name: 'Email it again' })).toBeEnabled()

    const config = record.mock.calls[0]![0]
    expect(config.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
    expect(body(config)).toMatchObject({ amount: '500.00', method: 'UPI', expectedVersion: 0 })
  })

  it('reuses the same key when the same request is retried, and a new one when it changed', async () => {
    const keys: string[] = []
    let attempt = 0
    const record: MockHandler = (config) => {
      keys.push(config.headers.get('Idempotency-Key') as string)
      attempt += 1
      return attempt < 3
        ? problem(503, 'SERVICE_UNAVAILABLE')
        : {
            status: 201,
            data: { payment: payment(), invoice: invoice({ status: 'PARTIAL' }), replayed: false },
          }
    }
    paymentApi({ 'POST /community/invoices/inv1/payments': record })
    const { user, sheet } = await openForm()
    const amount = within(sheet).getByLabelText(/Amount received/)
    await user.clear(amount)
    await user.type(amount, '500')
    const submit = within(sheet).getByRole('button', { name: 'Record payment' })

    await user.click(submit)
    await waitFor(() => expect(keys).toHaveLength(1))
    await waitFor(() => expect(submit).toBeEnabled())
    await user.click(submit)
    await waitFor(() => expect(keys).toHaveLength(2))
    expect(keys[1]).toBe(keys[0])

    await waitFor(() => expect(submit).toBeEnabled())
    await user.clear(amount)
    await user.type(amount, '400')
    await user.click(submit)
    await waitFor(() => expect(keys).toHaveLength(3))
    expect(keys[2]).not.toBe(keys[0])
  })

  it('shows the server refusing an overpayment', async () => {
    paymentApi({
      'POST /community/invoices/inv1/payments': () =>
        problem(422, 'OVERPAYMENT', 'The payment is more than the balance.'),
    })
    const { user, sheet } = await openForm()
    await user.click(within(sheet).getByRole('button', { name: 'Record payment' }))
    expect(await within(sheet).findByRole('alert')).toHaveTextContent(/more than the balance/i)
  })
})

// ---- invoice detail ---------------------------------------------------------------------------

describe('invoice detail', () => {
  const paid = detail({
    invoice: invoice({ status: 'PARTIAL', amountPaid: '500.00', balance: '1000.00', version: 1 }),
    payments: [payment()],
    reminders: [{ kind: 'OVERDUE', on: '2026-10-12' }],
  })

  it('shows what happened, oldest first', async () => {
    api({ 'GET /community/invoices/inv1': ok(paid) })
    render('/admin/billing/invoices/inv1')
    const timeline = await screen.findByRole('list', { name: 'What happened' })
    const items = within(timeline).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('Issued as INV-0001')
    expect(items[1]).toHaveTextContent('₹500.00 received by upi')
    expect(items[2]).toHaveTextContent('An overdue notice was queued')
  })

  it('says payments are confirmed by hand and explains a missing UPI id', async () => {
    api({
      'GET /community/invoices/inv1': ok(paid),
      'GET /community/invoices/inv1/qr': () => problem(409, 'UPI_NOT_CONFIGURED'),
    })
    render('/admin/billing/invoices/inv1')
    expect(await screen.findByRole('note')).toHaveTextContent(/confirmed by you, by hand/i)
    expect(await screen.findByRole('link', { name: /settings/i })).toBeInTheDocument()
  })

  it('needs a reason to reverse a payment, then sends it with an Idempotency-Key', async () => {
    const reverse = vi.fn<MockHandler>(() => ({
      status: 201,
      data: {
        payment: payment({ id: 'pay2', kind: 'REVERSAL', amount: '-500.00', reversedOf: 'pay1' }),
        invoice: invoice(),
        replayed: false,
      },
    }))
    api({
      'GET /community/invoices/inv1': ok(paid),
      'POST /community/payments/pay1/reverse': reverse,
    })
    const user = userEvent.setup()
    render('/admin/billing/invoices/inv1')
    await user.click(await screen.findByRole('button', { name: 'Reverse the payment of ₹500.00' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reverse this payment?' })
    expect(within(dialog).getByText(/Nothing is deleted/)).toBeInTheDocument()

    const confirm = within(dialog).getByRole('button', { name: 'Reverse payment' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Reason (required)'), 'Cheque bounced')
    await user.click(confirm)

    await waitFor(() => expect(reverse).toHaveBeenCalledTimes(1))
    const config = reverse.mock.calls[0]![0]
    expect(body(config)).toEqual({ reason: 'Cheque bounced' })
    expect(config.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/)
  })
})

// ---- defaulters -------------------------------------------------------------------------------

describe('defaulters', () => {
  it('shows the count before reminding everyone overdue', async () => {
    const remind = vi.fn<MockHandler>(() => ({
      status: 200,
      data: { overdueInvoices: 2, queued: 1, alreadyRemindedToday: 0, skipped: 1 },
    }))
    api({
      'GET /community/reminders/defaulters': ok({
        items: [
          {
            memberId: 'm1',
            memberNo: 'M-1',
            fullName: 'Asha Rao',
            email: 'a@x.in',
            overdueInvoices: 1,
            overdueAmount: '1500.00',
            oldestDueDate: '2026-09-10',
            lastRemindedOn: null,
          },
          {
            memberId: 'm2',
            memberNo: 'M-2',
            fullName: 'Ravi Nair',
            email: null,
            overdueInvoices: 1,
            overdueAmount: '1500.00',
            oldestDueDate: '2026-09-10',
            lastRemindedOn: null,
          },
        ],
        total: 2,
        page: 0,
        size: 20,
      }),
      'POST /community/reminders/overdue': remind,
    })
    const user = userEvent.setup()
    render('/admin/billing?tab=defaulters')
    await user.click(await screen.findByRole('button', { name: /Send a reminder to all overdue/ }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Remind 2 members?')
    expect(remind).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Send reminders' }))
    await waitFor(() => expect(remind).toHaveBeenCalledTimes(1))
  })
})

// ---- budget -----------------------------------------------------------------------------------

describe('budget', () => {
  it('shows income, expense, net and the opening balance for the period', async () => {
    api({
      'GET /community/ledger/summary': ok({
        from: '2026-04-01',
        to: '2027-03-31',
        openingBalance: '10000.00',
        totalIncome: '48000.50',
        totalExpense: '12000.25',
        net: '36000.25',
        closingBalance: '46000.25',
        byCategory: [],
        byMonth: [],
      }),
    })
    render('/admin/budget')
    const summary = await screen.findByRole('region', { name: 'Summary' })
    await waitFor(() => expect(summary).toHaveTextContent('₹48,000.50'))
    expect(summary).toHaveTextContent('₹12,000.25')
    expect(summary).toHaveTextContent('₹36,000.25')
    expect(summary).toHaveTextContent('₹10,000.00')
  })

  it('shows payment-linked entries read-only with a link to the payment, and reversals', async () => {
    const entry = (over: Record<string, unknown>) => ({
      id: 'e1',
      type: 'INCOME',
      category: { id: 'c1', name: 'Maintenance' },
      amount: '500.00',
      entryDate: '2026-10-05',
      title: 'Payment INV-0001',
      notes: null,
      attachmentKey: null,
      attachmentUrl: null,
      source: 'PAYMENT',
      sourceId: 'pay1',
      reversedOf: null,
      reversalReason: null,
      reversed: false,
      readOnly: true,
      createdAt: '2026-10-05T10:00:00Z',
      ...over,
    })
    api({
      'GET /community/ledger/entries': ok({
        items: [
          entry({}),
          entry({
            id: 'e2',
            title: 'Reversal of INV-0001',
            reversedOf: 'e1',
            reversalReason: 'Cheque bounced',
          }),
        ],
        total: 2,
        page: 0,
        size: 20,
      }),
    })
    render('/admin/budget')
    expect(await screen.findByText('Payment INV-0001')).toBeInTheDocument()
    expect(screen.getByText('Reversal of INV-0001')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Edit/ })).not.toBeInTheDocument()
  })
})

// ---- reports ----------------------------------------------------------------------------------

describe('reports', () => {
  const planWith = (features: Record<string, boolean>) =>
    ok({
      planCode: 'STARTER',
      planName: 'Starter',
      limits: {},
      features,
      usage: { members: 0, emailsThisMonth: 0 },
      communityStatus: 'ACTIVE',
      subscription: null,
      expiringWindowDays: 30,
    })

  it('offers an upgrade instead of PDF when the plan lacks it', async () => {
    api({ 'GET /community/plan': planWith({ csv_export: true, pdf_reports: false }) })
    render('/admin/budget?tab=reports')
    expect(
      await screen.findByText(/PDF reports are not part of the Starter plan/),
    ).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /PDF/ })).toBeDisabled()
    expect(screen.getByRole('radio', { name: /CSV/ })).toBeEnabled()
    expect(screen.getByRole('link', { name: 'Ask about upgrading' })).toBeInTheDocument()
  })

  it('has no upgrade prompt when both formats are included', async () => {
    api({ 'GET /community/plan': planWith({ csv_export: true, pdf_reports: true }) })
    render('/admin/budget?tab=reports')
    expect(await screen.findByRole('radio', { name: /PDF/ })).toBeEnabled()
    expect(screen.queryByText(/not part of the/)).not.toBeInTheDocument()
  })
})
