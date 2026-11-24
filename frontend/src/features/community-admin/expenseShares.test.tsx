import { cleanup, configure, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { InternalAxiosRequestConfig } from 'axios'
import { createQueryClient } from '@/lib/queryClient'
import { emptyCommunityApi } from '@/test/communityApi'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi, type MockHandler } from '@/test/mockApi'
import { renderApp } from '@/test/renderApp'
import type { InvoiceStatus, SharesView } from '@/types'

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

const expense = (over: Record<string, unknown> = {}) => ({
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
  ...over,
})
const ledger = (items: unknown[]) => ok({ items, total: items.length, page: 0, size: 20 })

const member = (n: number, name: string) => ({
  id: `m${n}`,
  memberNo: `M-00${n}`,
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
})
const members = ok({
  items: [member(1, 'Asha'), member(2, 'Ben'), member(3, 'Chitra')],
  total: 3,
  page: 0,
  size: 100,
})

const share = (n: number, name: string, over: Record<string, unknown> = {}) => ({
  invoiceId: `inv${n}`,
  invoiceNo: `INV-000${n}`,
  memberId: `m${n}`,
  memberNo: `M-00${n}`,
  memberName: name,
  amount: '333.33',
  amountPaid: '0.00',
  balance: '333.33',
  status: 'ISSUED' as InvoiceStatus,
  dueDate: '2026-11-30',
  ...over,
})
const shares = (over: Partial<SharesView> = {}): SharesView => ({
  expenseId: 'e1',
  title: 'Stairwell repair',
  expenseAmount: '1000.00',
  divided: '1000.00',
  collected: '333.33',
  pending: '666.67',
  shares: [
    share(1, 'Asha', { status: 'PAID', amountPaid: '333.33', balance: '0.00' }),
    share(2, 'Ben'),
    share(3, 'Chitra'),
  ],
  ...over,
})
const noShares = (): SharesView =>
  shares({ divided: '0.00', collected: '0.00', pending: '0.00', shares: [] })

async function openCollect() {
  const user = userEvent.setup()
  render('/admin/budget')
  await screen.findByText('Stairwell repair')
  await user.click(await screen.findByRole('button', { name: 'Actions for Stairwell repair' }))
  await user.click(await screen.findByRole('menuitem', { name: 'Collect from members' }))
  const sheet = await screen.findByRole('dialog', { name: 'Collect from members' })
  return { user, sheet }
}

describe('collecting an expense from members', () => {
  it('offers it on an expense, not on income or on a payment', async () => {
    api({
      'GET /community/ledger/entries': ledger([
        expense({ id: 'e2', type: 'INCOME', title: 'Grant' }),
        expense({
          id: 'e3',
          title: 'Payment INV-1',
          type: 'INCOME',
          source: 'PAYMENT',
          readOnly: true,
        }),
      ]),
    })
    const user = userEvent.setup()
    render('/admin/budget')
    await screen.findByText('Grant')
    await user.click(await screen.findByRole('button', { name: 'Actions for Grant' }))
    expect(await screen.findByRole('menuitem', { name: 'Edit' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Collect from members' })).not.toBeInTheDocument()
  })

  it('shows what each member would pay, then divides and says how many were emailed', async () => {
    let divided = false
    const divide = vi.fn<MockHandler>(() => {
      divided = true
      return {
        status: 201,
        data: {
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
      }
    })
    api({
      'GET /community/ledger/entries': ledger([expense()]),
      'GET /community/members': members,
      'GET /community/expenses/e1/shares': () => ({
        status: 200,
        data: divided ? shares() : noShares(),
      }),
      'POST /community/expenses/e1/divide': divide,
    })
    const { user, sheet } = await openCollect()

    // Everyone is the default; the preview is exact, not a rounded guess.
    expect(await within(sheet).findByText(/All active members \(3\)/)).toBeInTheDocument()
    expect(
      await within(sheet).findByText(
        /3 members pay ₹333\.33 or ₹333\.34, adding up to exactly ₹1,000\.00/,
      ),
    ).toBeInTheDocument()

    await user.click(within(sheet).getByRole('button', { name: 'Divide and send bills' }))
    await waitFor(() => expect(divide).toHaveBeenCalledTimes(1))
    const sent = body(divide.mock.calls[0]![0])
    expect(sent.sendEmails).toBe(true)
    expect(sent.memberIds).toBeUndefined() // everyone
    expect(sent.dueDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)

    // The panel turns into the progress view.
    expect(await within(sheet).findByRole('list', { name: 'Shares' })).toBeInTheDocument()
    expect(within(sheet).getByText('Still to collect')).toBeInTheDocument()
  })

  it('can bill chosen members only', async () => {
    const divide = vi.fn<MockHandler>(() => ({
      status: 201,
      data: {
        expenseId: 'e1',
        total: '1000.00',
        members: 2,
        firstShare: '500.00',
        lastShare: '500.00',
        emailsQueued: 2,
        emailsSkippedNoAddress: 0,
        emailsSkippedNoConsent: 0,
        emailsSkippedQuota: 0,
      },
    }))
    api({
      'GET /community/ledger/entries': ledger([expense()]),
      'GET /community/members': members,
      'GET /community/expenses/e1/shares': ok(noShares()),
      'POST /community/expenses/e1/divide': divide,
    })
    const { user, sheet } = await openCollect()

    await user.click(await within(sheet).findByRole('radio', { name: 'Chosen members' }))
    expect(await within(sheet).findByText('Choose who pays.')).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Divide and send bills' })).toBeDisabled()

    await user.click(await within(sheet).findByRole('checkbox', { name: /Asha/ }))
    await user.click(within(sheet).getByRole('checkbox', { name: /Ben/ }))
    expect(await within(sheet).findByText(/2 members pay ₹500\.00 each/)).toBeInTheDocument()

    await user.click(within(sheet).getByRole('button', { name: 'Divide and send bills' }))
    await waitFor(() => expect(divide).toHaveBeenCalledTimes(1))
    expect(body(divide.mock.calls[0]![0]).memberIds).toEqual(['m1', 'm2'])
  })

  it('can skip the emails', async () => {
    const divide = vi.fn<MockHandler>(() => ({
      status: 201,
      data: {
        expenseId: 'e1',
        total: '1000.00',
        members: 3,
        firstShare: '333.34',
        lastShare: '333.33',
        emailsQueued: 0,
        emailsSkippedNoAddress: 0,
        emailsSkippedNoConsent: 0,
        emailsSkippedQuota: 0,
      },
    }))
    api({
      'GET /community/ledger/entries': ledger([expense()]),
      'GET /community/members': members,
      'GET /community/expenses/e1/shares': ok(noShares()),
      'POST /community/expenses/e1/divide': divide,
    })
    const { user, sheet } = await openCollect()
    await user.click(await within(sheet).findByRole('checkbox', { name: /Email each member/ }))
    await user.click(within(sheet).getByRole('button', { name: 'Divide and send bills' }))
    await waitFor(() => expect(divide).toHaveBeenCalledTimes(1))
    expect(body(divide.mock.calls[0]![0]).sendEmails).toBe(false)
  })

  it('shows who has paid once it is divided, and lets cash be recorded on an unpaid share', async () => {
    api({
      'GET /community/ledger/entries': ledger([expense()]),
      'GET /community/expenses/e1/shares': ok(shares()),
    })
    const { sheet } = await openCollect()

    const list = await within(sheet).findByRole('list', { name: 'Shares' })
    const rows = within(list).getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(within(sheet).getByText('Divided')).toBeInTheDocument()
    expect(within(sheet).getByText('Collected')).toBeInTheDocument()
    expect(sheet).toHaveTextContent('₹666.67')

    // Asha has paid: nothing to record. The others can be paid in cash, or cancelled while unpaid.
    expect(within(rows[0]!).queryByRole('button', { name: /Record cash/ })).not.toBeInTheDocument()
    expect(
      within(rows[1]!).getByRole('button', { name: 'Record cash from Ben' }),
    ).toBeInTheDocument()
    expect(
      within(rows[1]!).getByRole('button', { name: 'Cancel the share of Ben' }),
    ).toBeInTheDocument()
  })

  it('offers only cash when recording a payment', async () => {
    api({
      'GET /community/ledger/entries': ledger([expense()]),
      'GET /community/expenses/e1/shares': ok(shares()),
      'GET /community/invoices/inv2': ok({
        invoice: {
          id: 'inv2',
          invoiceNo: 'INV-0002',
          status: 'ISSUED',
          kind: 'OTHER',
          memberId: 'm2',
          memberNo: 'M-002',
          memberName: 'Ben',
          feePlanId: null,
          period: null,
          description: 'Share of: Stairwell repair',
          amount: '333.33',
          amountPaid: '0.00',
          balance: '333.33',
          issuedOn: '2026-10-05',
          dueDate: '2026-11-30',
          cancelReason: null,
          cancelledAt: null,
          version: 0,
          createdAt: '2026-10-05T10:00:00Z',
        },
        payments: [],
        reminders: [],
      }),
    })
    const { user, sheet } = await openCollect()
    await user.click(await within(sheet).findByRole('button', { name: 'Record cash from Ben' }))
    const form = await screen.findByRole('dialog', { name: /Record a payment|Record payment/i })
    expect(within(form).getByRole('combobox', { name: /How was it paid/ })).toHaveTextContent(
      'Cash',
    )
    await user.click(within(form).getByRole('combobox', { name: /How was it paid/ }))
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['Cash'])
  })
})
