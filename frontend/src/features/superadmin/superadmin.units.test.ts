import { toQuery } from './api/params'
import { recordPaymentSchema, defaultPeriodEnd } from './paymentSchema'
import {
  EMPTY_PLAN_FORM,
  formToLimits,
  formToPublicPlan,
  planSchema,
  planToForm,
  type PlanFormInput,
} from './planDefinition'

describe('toQuery', () => {
  it('repeats array keys (Spring style) and leaves out empty values', () => {
    const query = toQuery({
      page: 0,
      size: 20,
      q: '',
      status: undefined,
      sort: ['name,asc', 'id,desc'],
    })
    expect(query.toString()).toBe('page=0&size=20&sort=name%2Casc&sort=id%2Cdesc')
  })
})

describe('plan form', () => {
  const valid: PlanFormInput = {
    ...EMPTY_PLAN_FORM,
    code: 'GROWTH',
    name: 'Growth',
    priceMonthly: '999',
    priceYearly: '9990.50',
  }

  it('accepts a complete plan', () => {
    expect(planSchema.safeParse(valid).success).toBe(true)
  })

  it.each([
    ['code', { code: 'growth' }],
    ['code', { code: 'A'.repeat(31) }],
    ['name', { name: '  ' }],
    ['priceMonthly', { priceMonthly: '12.345' }],
    ['priceYearly', { priceYearly: '-5' }],
    ['sortOrder', { sortOrder: '100001' }],
    ['sortOrder', { sortOrder: 'x' }],
  ])('rejects a bad %s', (field, change) => {
    const result = planSchema.safeParse({ ...valid, ...change })
    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path[0]).toBe(field)
  })

  it('rejects a limit that is not a whole number', () => {
    const result = planSchema.safeParse({
      ...valid,
      limits: { ...valid.limits, storage_mb: '1.5' },
    })
    expect(result.success).toBe(false)
  })

  it('sends an empty limit as null (unlimited) and a number as a number', () => {
    expect(
      formToLimits({
        max_members: '',
        storage_mb: '500',
        emails_per_month: '',
        emails_per_day: '',
      }),
    ).toEqual({ max_members: null, storage_mb: 500, emails_per_month: null, emails_per_day: null })
  })

  it('round trips a stored plan: null limits show as empty', () => {
    const form = planToForm({
      code: 'S',
      name: 'S',
      priceMonthly: '1.00',
      priceYearly: '10.00',
      limits: { max_members: null, storage_mb: 500 },
      features: { upi_qr: true },
      publicPlan: true,
      active: true,
      sortOrder: 5,
    })
    expect(form.limits).toEqual({
      max_members: '',
      storage_mb: '500',
      emails_per_month: '',
      emails_per_day: '',
    })
    expect(form.features.upi_qr).toBe(true)
    expect(form.features.pdf_reports).toBe(false)
  })

  it('previews half-typed values without throwing', () => {
    const preview = formToPublicPlan({ ...EMPTY_PLAN_FORM, priceMonthly: '12.' })
    expect(preview.priceMonthly).toBe('0')
    expect(preview.name).toBe('Plan name')
  })
})

describe('record payment form', () => {
  const valid = {
    communityId: 'c',
    planId: 'p',
    amount: '4990',
    reference: '',
    periodStart: '2026-01-01',
    periodEnd: '2026-12-31',
    paidOn: '',
  }

  it('accepts a payment, with or without the optional fields', () => {
    expect(recordPaymentSchema.safeParse(valid).success).toBe(true)
  })

  it('refuses a period that ends before it starts, on the end date field', () => {
    const result = recordPaymentSchema.safeParse({ ...valid, periodEnd: '2025-12-31' })
    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path).toEqual(['periodEnd'])
  })

  it('refuses a missing community, plan or amount', () => {
    const result = recordPaymentSchema.safeParse({
      ...valid,
      communityId: '',
      planId: '',
      amount: '',
    })
    expect(result.error?.issues.map((issue) => issue.path[0])).toEqual(
      expect.arrayContaining(['communityId', 'planId', 'amount']),
    )
  })

  it('suggests one year of service: the day before the anniversary', () => {
    expect(defaultPeriodEnd('2026-01-01')).toBe('2026-12-31')
    expect(defaultPeriodEnd('2026-10-17')).toBe('2027-10-16')
    expect(defaultPeriodEnd('2024-02-29')).toBe('2025-02-27') // leap day: Feb 28 anniversary
  })
})
