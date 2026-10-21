import { addMoney, compareMoney, isPositiveMoney, sumMoney } from '@/lib/money'
import type { InvoiceDetail, InvoiceView } from '@/types'
import { describeRun } from './billingFormat'
import {
  EMPTY_FEE_PLAN,
  feePlanSchema,
  generateSchema,
  recordPaymentSchema,
  toCreateFeePlanRequest,
  toRecordPaymentRequest,
} from './billingSchemas'
import { categoriesFor, entrySchema, toUpdateEntryRequest } from './budgetSchemas'
import { financialYear, rangeFor, thisMonth, thisQuarter } from './dateRanges'
import { buildTimeline } from './invoiceTimeline'

describe('money arithmetic that billing relies on', () => {
  it('adds amounts that floats get wrong', () => {
    expect(addMoney('0.10', '0.20')).toBe('0.30')
    expect(sumMoney(['1499.99', '0.01', '0.10', '0.20'])).toBe('1500.30')
  })

  it('compares by value, not by text', () => {
    expect(compareMoney('1000.00', '999.99')).toBe(1)
    expect(compareMoney('10', '10.00')).toBe(0)
    expect(compareMoney('9.5', '10')).toBe(-1)
  })

  it('treats zero as not positive', () => {
    expect(isPositiveMoney('0.00')).toBe(false)
    expect(isPositiveMoney('0.01')).toBe(true)
  })
})

describe('record payment validation', () => {
  const schema = recordPaymentSchema('1500.00')
  const valid = { amount: '500', method: 'UPI', reference: '', receivedOn: '2026-01-05' } as const

  it('accepts a partial payment', () => {
    expect(schema.safeParse(valid).success).toBe(true)
  })

  it('accepts exactly the balance', () => {
    expect(schema.safeParse({ ...valid, amount: '1500.00' }).success).toBe(true)
  })

  it('refuses one paisa over the balance, naming the balance', () => {
    const result = schema.safeParse({ ...valid, amount: '1500.01' })
    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.message).toBe(
      'That is more than the balance of ₹1,500.00. Enter the balance or less.',
    )
  })

  it.each(['', '0', '0.00', '-5', 'abc', '10.999'])('refuses the amount %j', (amount) => {
    expect(schema.safeParse({ ...valid, amount }).success).toBe(false)
  })

  it('refuses a future date', () => {
    expect(schema.safeParse({ ...valid, receivedOn: '2999-01-01' }).success).toBe(false)
  })

  it('sends the amount as the string it was typed and the invoice version', () => {
    const values = schema.parse({ ...valid, amount: '500.50', reference: ' UTR1 ' })
    expect(toRecordPaymentRequest(values, 3)).toEqual({
      amount: '500.50',
      method: 'UPI',
      receivedOn: '2026-01-05',
      expectedVersion: 3,
      reference: 'UTR1',
    })
  })

  it('leaves out an empty reference', () => {
    const values = schema.parse(valid)
    expect(toRecordPaymentRequest(values, 1)).not.toHaveProperty('reference')
  })
})

describe('invoice generation validation', () => {
  it('needs a plan; a recurring plan needs no period', () => {
    const schema = generateSchema(false)
    expect(
      schema.safeParse({ feePlanId: '', period: '', dueDate: '', sendEmails: true }).success,
    ).toBe(false)
    expect(
      schema.safeParse({ feePlanId: 'p1', period: '', dueDate: '', sendEmails: true }).success,
    ).toBe(true)
  })

  it('needs a name and a due date for a one-time plan', () => {
    const schema = generateSchema(true)
    expect(
      schema.safeParse({ feePlanId: 'p1', period: '', dueDate: '', sendEmails: true }).success,
    ).toBe(false)
    expect(
      schema.safeParse({
        feePlanId: 'p1',
        period: 'Annual day 2026',
        dueDate: '2026-12-01',
        sendEmails: false,
      }).success,
    ).toBe(true)
  })

  it('describes a run in words', () => {
    expect(describeRun({ created: 32, alreadyBilled: 5 })).toBe('32 created, 5 already existed')
    expect(describeRun({ created: 3, alreadyBilled: 0 })).toBe('3 created')
    expect(describeRun({ created: 0, alreadyBilled: 37 })).toBe('0 created, 37 already existed')
  })
})

describe('fee plan validation', () => {
  const valid = { ...EMPTY_FEE_PLAN, name: 'Monthly maintenance', amount: '1500' }

  it('accepts a monthly plan for everyone', () => {
    const parsed = feePlanSchema.parse(valid)
    expect(toCreateFeePlanRequest(parsed)).toMatchObject({
      amount: '1500',
      dueDay: 10,
      appliesTo: 'ALL_ACTIVE',
    })
  })

  it.each(['0', '29', 'x'])('refuses a due day of %j', (dueDay) => {
    expect(feePlanSchema.safeParse({ ...valid, dueDay }).success).toBe(false)
  })

  it('needs a group, or members, for those audiences', () => {
    expect(feePlanSchema.safeParse({ ...valid, appliesTo: 'GROUP' }).success).toBe(false)
    expect(
      feePlanSchema.safeParse({ ...valid, appliesTo: 'GROUP', group: 'Block A' }).success,
    ).toBe(true)
    expect(feePlanSchema.safeParse({ ...valid, appliesTo: 'SELECTED' }).success).toBe(false)
  })

  it('does not bill a one-time plan automatically', () => {
    expect(
      feePlanSchema.safeParse({ ...valid, frequency: 'ONE_TIME', autoGenerate: true }).success,
    ).toBe(false)
  })
})

describe('ledger entries', () => {
  const entry = {
    type: 'EXPENSE',
    categoryId: 'c1',
    amount: '250.00',
    entryDate: '2026-01-05',
    title: 'Repairs',
    notes: '',
    attachmentKey: '',
  } as const

  it('keeps the amount a string and refuses zero', () => {
    expect(entrySchema.parse(entry).amount).toBe('250.00')
    expect(entrySchema.safeParse({ ...entry, amount: '0' }).success).toBe(false)
  })

  it('sends only what changed, and never the amount', () => {
    const values = entrySchema.parse({ ...entry, title: 'Lift repairs' })
    expect(toUpdateEntryRequest(values, entry)).toEqual({ title: 'Lift repairs' })
  })

  it('offers only active categories of the same type', () => {
    const categories = [
      { id: '1', name: 'Repairs', type: 'EXPENSE', active: true, system: false, entryCount: 0 },
      { id: '2', name: 'Old', type: 'EXPENSE', active: false, system: false, entryCount: 0 },
      { id: '3', name: 'Donations', type: 'INCOME', active: true, system: false, entryCount: 0 },
    ] as const
    expect(categoriesFor(categories, 'EXPENSE').map((c) => c.name)).toEqual(['Repairs'])
  })
})

describe('date ranges', () => {
  it('finds the month and quarter', () => {
    expect(thisMonth('2026-02-10')).toEqual({ from: '2026-02-01', to: '2026-02-28' })
    expect(thisQuarter('2026-05-20')).toEqual({ from: '2026-04-01', to: '2026-06-30' })
  })

  it('finds the financial year from either side of its start', () => {
    expect(financialYear('2026-10-18', 4)).toEqual({ from: '2026-04-01', to: '2027-03-31' })
    expect(financialYear('2027-02-01', 4)).toEqual({ from: '2026-04-01', to: '2027-03-31' })
    expect(financialYear('2026-03-31', 4)).toEqual({ from: '2025-04-01', to: '2026-03-31' })
    expect(financialYear('2026-06-01', 1)).toEqual({ from: '2026-01-01', to: '2026-12-31' })
  })

  it('maps a preset to its range', () => {
    expect(rangeFor('year', '2026-10-18', 4).from).toBe('2026-04-01')
  })
})

describe('invoice timeline', () => {
  const invoice = {
    id: 'i1',
    invoiceNo: 'INV-1',
    amount: '1500.00',
    dueDate: '2026-01-10',
    issuedOn: '2026-01-01',
    cancelledAt: null,
    cancelReason: null,
  } as unknown as InvoiceView
  const payment = (over: Record<string, unknown>) => ({
    id: 'p1',
    kind: 'PAYMENT',
    amount: '500.00',
    method: 'UPI',
    reference: null,
    createdAt: '2026-01-05T10:00:00Z',
    receiptId: 'r1',
    receiptNo: 'RCT-1',
    reversed: false,
    reversalReason: null,
    ...over,
  })

  it('lists issue, reminder, payment and reversal oldest first', () => {
    const detail = {
      invoice,
      reminders: [{ kind: 'OVERDUE', on: '2026-01-12' }],
      payments: [
        payment({
          id: 'p2',
          kind: 'REVERSAL',
          amount: '500.00',
          createdAt: '2026-01-20T10:00:00Z',
          receiptId: null,
          receiptNo: null,
          reversalReason: 'Cheque bounced',
        }),
        payment({ reversed: true }),
      ],
    } as unknown as InvoiceDetail
    const events = buildTimeline(detail)
    expect(events.map((e) => e.kind)).toEqual(['issued', 'payment', 'reminder', 'reversal'])
    expect(events[1]).toMatchObject({ receiptNo: 'RCT-1', reversed: true })
    expect(events[3]?.title).toBe('-₹500.00 was reversed')
    expect(events[3]?.detail).toBe('Reason: Cheque bounced')
  })
})
