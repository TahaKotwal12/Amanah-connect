import { expect, test, type BrowserContext, type Route } from '@playwright/test'

/**
 * Money, end to end in a real browser: bill a fee plan, record a partial payment, find its receipt,
 * see the income in the ledger, then reverse the payment. The API is a small stand-in that keeps
 * the invoices, payments and ledger rows that matter, and does the sums the way the server does.
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

const PLAN = {
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

const MEMBERS = [
  { id: 'm1', memberNo: 'M-0001', name: 'Asha Rao' },
  { id: 'm2', memberNo: 'M-0002', name: 'Ravi Nair' },
]

// Money in integer paise, so the stand-in never does float arithmetic either.
const rupees = (paise: number) =>
  `${Math.trunc(paise / 100)}.${String(paise % 100).padStart(2, '0')}`
const toPaise = (value: string) => {
  const [whole = '0', fraction = ''] = value.split('.')
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0').slice(0, 2))
}

interface Invoice {
  id: string
  invoiceNo: string
  memberId: string
  memberNo: string
  memberName: string
  amount: number
  paid: number
  version: number
}
interface Payment {
  id: string
  kind: 'PAYMENT' | 'REVERSAL'
  invoiceId: string
  amount: number
  reversedOf: string | null
  reversalReason: string | null
  reversed: boolean
  receiptId: string | null
  receiptNo: string | null
  createdAt: string
}
interface Entry {
  id: string
  title: string
  amount: number
  type: 'INCOME' | 'EXPENSE'
  paymentId: string
  reversedOf: string | null
}

async function fakeBilling(context: BrowserContext) {
  const invoices: Invoice[] = []
  const payments: Payment[] = []
  const ledger: Entry[] = []
  const keys: string[] = []
  const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, json: body })
  const page = <T>(items: T[]) => ({ items, total: items.length, page: 0, size: 20 })

  const invoiceView = (i: Invoice) => ({
    id: i.id,
    invoiceNo: i.invoiceNo,
    status: i.paid === 0 ? 'ISSUED' : i.paid >= i.amount ? 'PAID' : 'PARTIAL',
    kind: 'MAINTENANCE',
    memberId: i.memberId,
    memberNo: i.memberNo,
    memberName: i.memberName,
    feePlanId: 'fp1',
    period: '2026-10',
    description: 'Monthly maintenance',
    amount: rupees(i.amount),
    amountPaid: rupees(i.paid),
    balance: rupees(i.amount - i.paid),
    issuedOn: '2026-10-01',
    dueDate: '2026-10-10',
    cancelReason: null,
    cancelledAt: null,
    version: i.version,
    createdAt: '2026-10-01T00:00:00Z',
  })
  const paymentView = (p: Payment) => {
    const invoice = invoices.find((i) => i.id === p.invoiceId)
    return {
      id: p.id,
      kind: p.kind,
      invoiceId: p.invoiceId,
      invoiceNo: invoice?.invoiceNo ?? null,
      memberId: invoice?.memberId ?? null,
      memberNo: invoice?.memberNo ?? null,
      payerName: invoice?.memberName ?? null,
      amount: rupees(p.kind === 'REVERSAL' ? -p.amount : p.amount).replace('-0', '-0'),
      method: 'UPI',
      reference: null,
      receivedOn: '2026-10-05',
      reversedOf: p.reversedOf,
      reversalReason: p.reversalReason,
      reversed: p.reversed,
      receiptId: p.receiptId,
      receiptNo: p.receiptNo,
      createdAt: p.createdAt,
    }
  }
  const entryView = (e: Entry) => ({
    id: e.id,
    type: e.type,
    category: { id: 'c1', name: 'Maintenance' },
    amount: rupees(e.amount),
    entryDate: '2026-10-05',
    title: e.title,
    notes: null,
    attachmentKey: null,
    attachmentUrl: null,
    source: 'PAYMENT',
    sourceId: e.paymentId,
    reversedOf: e.reversedOf,
    reversalReason: null,
    reversed: false,
    readOnly: true,
    createdAt: '2026-10-05T10:00:00Z',
  })

  await context.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/v1', '')
    const key = `${request.method()} ${path}`
    const body = (request.postDataJSON() ?? {}) as Record<string, string>

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
        features: { csv_export: true },
        usage: { members: 2, emailsThisMonth: 0 },
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
        financialYearStartMonth: 4,
      })
    if (key === 'GET /community/fee-plans') return json(route, [PLAN])

    if (key === 'POST /community/invoices/generate/preview') {
      const todo = MEMBERS.length - invoices.length
      return json(route, {
        feePlanId: 'fp1',
        feePlanName: PLAN.name,
        period: '2026-10',
        dueDate: '2026-10-10',
        eligibleMembers: MEMBERS.length,
        willCreate: todo,
        alreadyBilled: invoices.length,
        skippedInactive: 0,
        amountEach: '1500.00',
        totalToBill: rupees(todo * 150000),
      })
    }
    if (key === 'POST /community/invoices/generate') {
      let created = 0
      for (const member of MEMBERS) {
        if (invoices.some((i) => i.memberId === member.id)) continue
        invoices.push({
          id: `inv${invoices.length + 1}`,
          invoiceNo: `INV-000${invoices.length + 1}`,
          memberId: member.id,
          memberNo: member.memberNo,
          memberName: member.name,
          amount: 150000,
          paid: 0,
          version: 0,
        })
        created += 1
      }
      return json(route, {
        feePlanId: 'fp1',
        period: '2026-10',
        dueDate: '2026-10-10',
        eligibleMembers: MEMBERS.length,
        created,
        alreadyBilled: MEMBERS.length - created,
        skippedInactive: 0,
        emailsQueued: 0,
        emailsSkippedNoAddress: 0,
        emailsSkippedNoConsent: 0,
        emailsSkippedQuota: 0,
        firstInvoiceNo: 'INV-0001',
        lastInvoiceNo: `INV-000${invoices.length}`,
      })
    }
    if (key === 'GET /community/invoices') return json(route, page(invoices.map(invoiceView)))
    if (key === 'GET /community/invoices/totals')
      return json(route, {
        count: invoices.length,
        amount: rupees(invoices.reduce((sum, i) => sum + i.amount, 0)),
        amountPaid: rupees(invoices.reduce((sum, i) => sum + i.paid, 0)),
        balance: rupees(invoices.reduce((sum, i) => sum + i.amount - i.paid, 0)),
      })

    const detail = /^GET \/community\/invoices\/(inv\d+)$/.exec(key)
    if (detail) {
      const invoice = invoices.find((i) => i.id === detail[1])
      if (!invoice) return json(route, { status: 404, code: 'NOT_FOUND', detail: 'none' }, 404)
      return json(route, {
        invoice: invoiceView(invoice),
        payments: payments.filter((p) => p.invoiceId === invoice.id).map(paymentView),
        reminders: [],
      })
    }
    if (/^GET \/community\/invoices\/inv\d+\/qr$/.test(key))
      return json(route, { status: 409, code: 'UPI_NOT_CONFIGURED', detail: 'No UPI id' }, 409)

    const record = /^POST \/community\/invoices\/(inv\d+)\/payments$/.exec(key)
    if (record) {
      const invoice = invoices.find((i) => i.id === record[1])
      if (!invoice) return json(route, { status: 404, code: 'NOT_FOUND', detail: 'none' }, 404)
      keys.push(request.headers()['idempotency-key'] ?? '')
      const amount = toPaise(String(body.amount))
      if (invoice.paid + amount > invoice.amount)
        return json(
          route,
          { status: 422, code: 'OVERPAYMENT', detail: 'More than the balance.' },
          422,
        )
      invoice.paid += amount
      invoice.version += 1
      const n = payments.length + 1
      const payment: Payment = {
        id: `pay${n}`,
        kind: 'PAYMENT',
        invoiceId: invoice.id,
        amount,
        reversedOf: null,
        reversalReason: null,
        reversed: false,
        receiptId: `rc${n}`,
        receiptNo: `RCT-000${n}`,
        createdAt: '2026-10-05T10:00:00Z',
      }
      payments.push(payment)
      ledger.push({
        id: `e${ledger.length + 1}`,
        title: `Payment ${invoice.invoiceNo}`,
        amount,
        type: 'INCOME',
        paymentId: payment.id,
        reversedOf: null,
      })
      return json(
        route,
        { payment: paymentView(payment), invoice: invoiceView(invoice), replayed: false },
        201,
      )
    }

    const reverse = /^POST \/community\/payments\/(pay\d+)\/reverse$/.exec(key)
    if (reverse) {
      const original = payments.find((p) => p.id === reverse[1])
      const invoice = invoices.find((i) => i.id === original?.invoiceId)
      if (!original || !invoice)
        return json(route, { status: 404, code: 'NOT_FOUND', detail: 'none' }, 404)
      original.reversed = true
      invoice.paid -= original.amount
      invoice.version += 1
      const reversal: Payment = {
        id: `pay${payments.length + 1}`,
        kind: 'REVERSAL',
        invoiceId: invoice.id,
        amount: original.amount,
        reversedOf: original.id,
        reversalReason: body.reason ?? null,
        reversed: false,
        receiptId: null,
        receiptNo: null,
        createdAt: '2026-10-06T10:00:00Z',
      }
      payments.push(reversal)
      ledger.push({
        id: `e${ledger.length + 1}`,
        title: `Reversal of payment ${invoice.invoiceNo}`,
        amount: original.amount,
        type: 'EXPENSE',
        paymentId: reversal.id,
        reversedOf: original.id,
      })
      return json(
        route,
        { payment: paymentView(reversal), invoice: invoiceView(invoice), replayed: false },
        201,
      )
    }

    if (key === 'GET /community/receipts')
      return json(
        route,
        page(
          payments
            .filter((p) => p.receiptId)
            .map((p) => ({
              id: p.receiptId,
              receiptNo: p.receiptNo,
              paymentId: p.id,
              invoiceId: p.invoiceId,
              invoiceNo: invoices.find((i) => i.id === p.invoiceId)?.invoiceNo ?? null,
              payerName: invoices.find((i) => i.id === p.invoiceId)?.memberName ?? null,
              amount: rupees(p.amount),
              method: 'UPI',
              reference: null,
              receivedOn: '2026-10-05',
              reversed: p.reversed,
              reversedOn: p.reversed ? '2026-10-06' : null,
              reversalReason: null,
              emailedAt: null,
              createdAt: p.createdAt,
            })),
        ),
      )
    if (/^GET \/community\/receipts\/rc\d+\/pdf$/.test(key))
      return route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4 e2e' })

    if (key === 'GET /community/ledger/entries') return json(route, page(ledger.map(entryView)))
    if (key === 'GET /community/ledger/categories') return json(route, [])
    if (key === 'GET /community/ledger/summary') {
      const income = ledger.filter((e) => e.type === 'INCOME').reduce((s, e) => s + e.amount, 0)
      const expense = ledger.filter((e) => e.type === 'EXPENSE').reduce((s, e) => s + e.amount, 0)
      return json(route, {
        from: '2026-04-01',
        to: '2027-03-31',
        openingBalance: '0.00',
        totalIncome: rupees(income),
        totalExpense: rupees(expense),
        net: rupees(income - expense),
        closingBalance: rupees(income - expense),
        byCategory: [],
        byMonth: [],
      })
    }
    return json(route, page([]))
  })
  return { invoices, payments, ledger, keys }
}

test('generate invoices, record a partial payment, see the receipt and the income, then reverse', async ({
  page,
  context,
}) => {
  const platform = await fakeBilling(context)

  // 1. Bill the fee plan. The preview creates nothing; the run says what it did.
  await page.goto('/admin/billing')
  await expect(page.getByRole('heading', { level: 1, name: 'Billing' })).toBeVisible()
  await page.getByRole('button', { name: 'Generate invoices' }).click()
  const wizard = page.getByRole('dialog', { name: 'Generate invoices' })
  await wizard.getByRole('combobox', { name: 'Fee plan' }).click()
  await page.getByRole('option', { name: /Monthly maintenance/ }).click()
  await wizard.getByRole('button', { name: 'See what would happen' }).click()
  await expect(wizard.getByText('Nothing has been created yet.')).toBeVisible()
  await expect(wizard.getByText('₹3,000.00')).toBeVisible()
  expect(platform.invoices).toHaveLength(0)
  await wizard.getByRole('button', { name: 'Create 2 invoices' }).click()
  await expect(wizard.getByText('2 created')).toBeVisible()
  await wizard.getByRole('button', { name: 'Close', exact: true }).first().click()
  expect(platform.invoices).toHaveLength(2)

  // 2. Record a partial payment on the first invoice.
  await expect(page.getByRole('link', { name: 'INV-0001' })).toBeVisible()
  await page.getByRole('link', { name: 'INV-0001' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'INV-0001' })).toBeVisible()
  await page.getByRole('button', { name: 'Record payment' }).click()
  const sheet = page.getByRole('dialog', { name: 'Record a payment' })
  const amount = sheet.getByLabel(/Amount received/)
  await expect(amount).toHaveValue('1500.00')
  await amount.click()
  await amount.fill('1500.01')
  await sheet.getByRole('button', { name: 'Record payment' }).click()
  await expect(sheet.getByText(/more than the balance of ₹1,500.00/)).toBeVisible()
  expect(platform.payments).toHaveLength(0)
  // Focus first: the box swaps its grouped display for the plain digits on focus.
  await amount.click()
  await amount.fill('500')
  await expect(amount).toHaveValue('500')
  await sheet.getByRole('button', { name: 'Record payment' }).click()

  // 3. The receipt is there to download.
  await expect(sheet.getByText('Payment recorded')).toBeVisible()
  await expect(sheet.getByText('RCT-0001')).toBeVisible()
  await expect(sheet.getByText('₹1,000.00')).toBeVisible()
  await expect(sheet.getByRole('button', { name: /Download receipt/ })).toBeEnabled()
  expect(platform.keys).toHaveLength(1)
  expect(platform.keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  await sheet.getByRole('button', { name: 'Done' }).click()
  await expect(page.getByText('Partly paid').first()).toBeVisible()

  // 4. The ledger shows the income, read-only.
  await page.goto('/admin/budget')
  await expect(page.getByText('Payment INV-0001')).toBeVisible()
  await expect(page.getByRole('region', { name: 'Summary' })).toContainText('₹500.00')

  // 5. Reverse it: a reason is required, nothing is deleted.
  await page.goto('/admin/billing/invoices/inv1')
  await page.getByRole('button', { name: 'Reverse the payment of ₹500.00' }).click()
  const dialog = page.getByRole('dialog', { name: 'Reverse this payment?' })
  const confirm = dialog.getByRole('button', { name: 'Reverse payment' })
  await expect(confirm).toBeDisabled()
  await dialog.getByLabel('Reason (required)').fill('Cheque bounced')
  await confirm.click()
  await expect(page.getByRole('list', { name: 'What happened' })).toContainText('was reversed')
  expect(platform.payments).toHaveLength(2)

  await page.goto('/admin/budget')
  await expect(page.getByText('Reversal of payment INV-0001')).toBeVisible()
  await expect(page.getByText('Payment INV-0001', { exact: true })).toBeVisible()
})
