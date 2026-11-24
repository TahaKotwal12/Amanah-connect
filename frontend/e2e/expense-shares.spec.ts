import { expect, test, type Route } from '@playwright/test'
import { COMMUNITY_ADMIN_ME, fakeApi } from './support/fakeApi.ts'

/**
 * Phase 1 end to end: the admin has an expense, divides it among the members, then records cash from
 * one member and the collected total moves. The API is a small stand-in that does the arithmetic the
 * way the server does (equal shares to the paisa, leftover paise to the first members).
 */
const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, json: body })

const EXPENSE = {
  id: 'e1',
  type: 'EXPENSE',
  category: { id: 'c1', name: 'Repairs' },
  amount: '1000.00',
  entryDate: '2026-10-05',
  title: 'Stairwell repair',
  notes: null,
  attachmentKey: null,
  attachmentUrl: null,
  source: 'MANUAL',
  sourceId: null,
  reversedOf: null,
  reversalReason: null,
  reversed: false,
  readOnly: false,
  createdAt: '2026-10-05T10:00:00Z',
}

const MEMBERS = ['Asha', 'Ben', 'Chitra'].map((name, i) => ({
  id: `m${i + 1}`,
  memberNo: `M-00${i + 1}`,
  fullName: name,
  email: `${name.toLowerCase()}@example.test`,
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
}))

interface Share {
  invoiceId: string
  invoiceNo: string
  memberId: string
  memberNo: string
  memberName: string
  amount: string
  amountPaid: string
  balance: string
  status: string
  dueDate: string
}

test('divide an expense among members, then record cash from one of them', async ({ page }) => {
  let shares: Share[] = []
  const posted: Array<Record<string, unknown>> = []

  const totals = () => {
    const live = shares.filter((s) => s.status !== 'CANCELLED')
    const cents = (v: string) => Math.round(parseFloat(v) * 100)
    const divided = live.reduce((sum, s) => sum + cents(s.amount), 0)
    const collected = live.reduce((sum, s) => sum + cents(s.amountPaid), 0)
    const money = (c: number) => (c / 100).toFixed(2)
    return {
      divided: money(divided),
      collected: money(collected),
      pending: money(divided - collected),
    }
  }

  await page.route('**/api/v1/auth/refresh', (route) =>
    json(route, {
      accessToken: 'e2e',
      tokenType: 'Bearer',
      expiresIn: 900,
      mfaSetupRequired: false,
    }),
  )
  await page.route('**/api/v1/auth/me', (route) => json(route, COMMUNITY_ADMIN_ME))
  await page.route('**/api/v1/community/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/v1/community', '')
    const key = `${request.method()} ${path}`
    const page0 = (items: unknown[]) => ({ items, total: items.length, page: 0, size: 20 })

    if (key === 'GET /ledger/entries') return json(route, page0([EXPENSE]))
    if (key === 'GET /members') return json(route, { ...page0(MEMBERS), size: 100 })
    if (key === 'GET /expenses/e1/shares') {
      return json(route, {
        expenseId: 'e1',
        title: EXPENSE.title,
        expenseAmount: EXPENSE.amount,
        ...totals(),
        shares,
      })
    }
    if (key === 'POST /expenses/e1/divide') {
      posted.push(request.postDataJSON() as Record<string, unknown>)
      // 1000.00 over 3 members: 333.34, 333.33, 333.33
      const amounts = ['333.34', '333.33', '333.33']
      shares = MEMBERS.map((m, i) => ({
        invoiceId: `inv${i + 1}`,
        invoiceNo: `INV-000${i + 1}`,
        memberId: m.id,
        memberNo: m.memberNo,
        memberName: m.fullName,
        amount: amounts[i] ?? '0.00',
        amountPaid: '0.00',
        balance: amounts[i] ?? '0.00',
        status: 'ISSUED',
        dueDate: '2026-12-01',
      }))
      return json(
        route,
        {
          expenseId: 'e1',
          total: '1000.00',
          members: 3,
          firstShare: '333.34',
          lastShare: '333.33',
          emailsQueued: 3,
          emailsSkippedNoAddress: 0,
          emailsSkippedNoConsent: 0,
          emailsSkippedQuota: 0,
        },
        201,
      )
    }
    const invoiceMatch = /^GET \/invoices\/(inv\d)$/.exec(key)
    if (invoiceMatch) {
      const s = shares.find((x) => x.invoiceId === invoiceMatch[1])
      if (!s) return json(route, { status: 404, code: 'NOT_FOUND', detail: 'x' }, 404)
      return json(route, {
        invoice: {
          id: s.invoiceId,
          invoiceNo: s.invoiceNo,
          status: s.status,
          kind: 'OTHER',
          memberId: s.memberId,
          memberNo: s.memberNo,
          memberName: s.memberName,
          feePlanId: null,
          period: null,
          description: 'Share of: Stairwell repair',
          amount: s.amount,
          amountPaid: s.amountPaid,
          balance: s.balance,
          issuedOn: '2026-10-05',
          dueDate: s.dueDate,
          cancelReason: null,
          cancelledAt: null,
          version: 0,
          createdAt: '2026-10-05T10:00:00Z',
        },
        payments: [],
        reminders: [],
      })
    }
    const payMatch = /^POST \/invoices\/(inv\d)\/payments$/.exec(key)
    if (payMatch) {
      const s = shares.find((x) => x.invoiceId === payMatch[1])
      if (!s) return json(route, { status: 404, code: 'NOT_FOUND', detail: 'x' }, 404)
      const body = request.postDataJSON() as { amount: string; method: string }
      expect(body.method).toBe('CASH')
      s.amountPaid = body.amount
      s.balance = '0.00'
      s.status = 'PAID'
      return json(
        route,
        {
          payment: {
            id: 'pay1',
            kind: 'PAYMENT',
            invoiceId: s.invoiceId,
            invoiceNo: s.invoiceNo,
            memberId: s.memberId,
            memberNo: s.memberNo,
            payerName: s.memberName,
            amount: body.amount,
            method: 'CASH',
            reference: null,
            receivedOn: '2026-10-06',
            reversedOf: null,
            reversalReason: null,
            reversed: false,
            receiptId: 'rc1',
            receiptNo: 'RCP-2026-27/000001',
            createdAt: '2026-10-06T10:00:00Z',
          },
          invoice: {
            id: s.invoiceId,
            status: 'PAID',
            amount: s.amount,
            amountPaid: s.amountPaid,
            balance: '0.00',
            version: 1,
          },
          replayed: false,
        },
        201,
      )
    }
    // everything else the pages read: empty but valid
    if (key === 'GET /ledger/categories' || key === 'GET /ledger/summary') {
      return json(
        route,
        key.endsWith('categories')
          ? []
          : {
              from: '2026-04-01',
              to: '2027-03-31',
              openingBalance: '0.00',
              totalIncome: '0.00',
              totalExpense: '1000.00',
              net: '-1000.00',
              closingBalance: '-1000.00',
              byCategory: [],
              byMonth: [],
            },
      )
    }
    if (key === 'GET /plan') {
      return json(route, {
        planCode: 'STARTER',
        planName: 'Starter',
        limits: {},
        features: {},
        usage: { members: 3, emailsThisMonth: 0 },
        communityStatus: 'ACTIVE',
        subscription: null,
        expiringWindowDays: 30,
      })
    }
    if (key === 'GET /notifications/summary') return json(route, { unread: 0, banners: [] })
    if (key === 'GET /settings') {
      return json(route, {
        name: 'Lotus Residents',
        logoUrl: null,
        currency: 'INR',
        memberGroupLabel: 'Block',
      })
    }
    return json(route, page0([]))
  })

  await page.goto('/admin/budget')
  await expect(page.getByText('Stairwell repair')).toBeVisible()

  // The extras are gone from the screen: only the ledger.
  await expect(page.getByRole('tab', { name: 'Reports' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: 'Categories' })).toHaveCount(0)

  // Collect from members: the preview is the real split.
  await page.getByRole('button', { name: 'Actions for Stairwell repair' }).click()
  await page.getByRole('menuitem', { name: 'Collect from members' }).click()
  const sheet = page.getByRole('dialog', { name: 'Collect from members' })
  await expect(
    sheet.getByText(/3 members pay ₹333\.33 or ₹333\.34, adding up to exactly ₹1,000\.00/),
  ).toBeVisible()
  await sheet.getByRole('button', { name: 'Divide and send bills' }).click()

  // Progress view: nothing collected yet.
  const list = sheet.getByRole('list', { name: 'Shares' })
  await expect(list.getByRole('listitem')).toHaveCount(3)
  expect(posted).toHaveLength(1)
  expect(posted[0]?.sendEmails).toBe(true)
  await expect(sheet.getByText('Still to collect')).toBeVisible()
  await expect(sheet).toContainText('₹1,000.00')

  // Cash from Ben: only "Cash" is offered, and the collected total moves.
  await sheet.getByRole('button', { name: 'Record cash from Ben' }).click()
  const form = page.getByRole('dialog', { name: /record/i }).last()
  await form.getByRole('button', { name: 'Record payment' }).click()
  await expect(form.getByText('Payment recorded')).toBeVisible()
  await expect(form.getByText('RCP-2026-27/000001')).toBeVisible()
})

test('the extras are switched off for the community admin', async ({ page }) => {
  await fakeApi(page, 'COMMUNITY_ADMIN')
  await page.goto('/admin/billing')
  await expect(page.getByRole('heading', { level: 1, name: 'Billing' })).toBeVisible()
  for (const name of ['New invoice', 'Generate invoices']) {
    await expect(page.getByRole('button', { name })).toHaveCount(0)
  }
  for (const name of ['Fee plans', 'Defaulters']) {
    await expect(page.getByRole('tab', { name })).toHaveCount(0)
  }
  await expect(page.getByRole('tab', { name: 'Invoices' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Receipts' })).toBeVisible()
})
