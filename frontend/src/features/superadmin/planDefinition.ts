import { z } from 'zod'
import { isMoneyString } from '@/lib/money'
import type { JsonObject } from '@/types'

/** The keys the backend accepts in a plan (PlanLimitKeys.java). Anything else is rejected there. */
export const LIMIT_FIELDS = [
  { key: 'max_members', label: 'Members', unit: 'members' },
  { key: 'storage_mb', label: 'File storage', unit: 'MB' },
  { key: 'emails_per_month', label: 'Emails a month', unit: 'emails' },
  { key: 'emails_per_day', label: 'Emails a day (optional cap)', unit: 'emails' },
] as const

export const FEATURE_FIELDS = [
  { key: 'upi_qr', label: 'UPI QR payments' },
  { key: 'receipts_email', label: 'Receipts by email' },
  { key: 'csv_export', label: 'CSV export' },
  { key: 'pdf_reports', label: 'PDF reports' },
] as const

export type LimitKey = (typeof LIMIT_FIELDS)[number]['key']
export type FeatureKey = (typeof FEATURE_FIELDS)[number]['key']

const wholeNumberOrEmpty = z
  .string()
  .trim()
  .refine((value) => value === '' || /^\d{1,9}$/.test(value), {
    message: 'Enter a whole number, or leave it empty for unlimited',
  })

const price = z
  .string()
  .trim()
  .min(1, 'Enter a price (0 for free)')
  .refine((value) => isMoneyString(value) && !value.startsWith('-'), {
    message: 'Enter an amount like 499 or 499.50',
  })

/** Mirrors CreatePlanRequest / UpdatePlanRequest and PlanDefinitionValidator on the backend. */
export const planSchema = z.object({
  code: z
    .string()
    .trim()
    .min(1, 'Enter a code')
    .max(30, 'Use 30 characters or fewer')
    .regex(/^[A-Z0-9_]+$/, 'Use upper case letters, digits and underscores'),
  name: z.string().trim().min(1, 'Enter a name').max(100, 'Use 100 characters or fewer'),
  priceMonthly: price,
  priceYearly: price,
  limits: z.object({
    max_members: wholeNumberOrEmpty,
    storage_mb: wholeNumberOrEmpty,
    emails_per_month: wholeNumberOrEmpty,
    emails_per_day: wholeNumberOrEmpty,
  }),
  features: z.object({
    upi_qr: z.boolean(),
    receipts_email: z.boolean(),
    csv_export: z.boolean(),
    pdf_reports: z.boolean(),
  }),
  publicPlan: z.boolean(),
  active: z.boolean(),
  sortOrder: z
    .string()
    .trim()
    .regex(/^\d{1,6}$/, 'Enter a whole number from 0 to 100000')
    .refine((value) => Number(value) <= 100000, {
      message: 'Enter a whole number from 0 to 100000',
    }),
})

export type PlanFormInput = z.input<typeof planSchema>
export type PlanFormValues = z.output<typeof planSchema>

export const EMPTY_PLAN_FORM: PlanFormInput = {
  code: '',
  name: '',
  priceMonthly: '',
  priceYearly: '',
  limits: { max_members: '', storage_mb: '', emails_per_month: '', emails_per_day: '' },
  features: { upi_qr: false, receipts_email: false, csv_export: false, pdf_reports: false },
  publicPlan: false,
  active: true,
  sortOrder: '100',
}

/** A stored plan as form values. Missing or null limits show as empty (unlimited). */
export function planToForm(plan: {
  code: string
  name: string
  priceMonthly: string
  priceYearly: string
  limits: JsonObject
  features: JsonObject
  publicPlan: boolean
  active: boolean
  sortOrder: number
}): PlanFormInput {
  const limit = (key: LimitKey): string => {
    const value = plan.limits[key]
    return typeof value === 'number' ? String(value) : ''
  }
  return {
    code: plan.code,
    name: plan.name,
    priceMonthly: plan.priceMonthly,
    priceYearly: plan.priceYearly,
    limits: {
      max_members: limit('max_members'),
      storage_mb: limit('storage_mb'),
      emails_per_month: limit('emails_per_month'),
      emails_per_day: limit('emails_per_day'),
    },
    features: {
      upi_qr: plan.features.upi_qr === true,
      receipts_email: plan.features.receipts_email === true,
      csv_export: plan.features.csv_export === true,
      pdf_reports: plan.features.pdf_reports === true,
    },
    publicPlan: plan.publicPlan,
    active: plan.active,
    sortOrder: String(plan.sortOrder),
  }
}

/** Form values as the API body. An empty limit is sent as null, which means unlimited. */
export function formToLimits(limits: PlanFormValues['limits']): JsonObject {
  const out: JsonObject = {}
  for (const { key } of LIMIT_FIELDS) {
    const text = limits[key]
    out[key] = text === '' ? null : Number(text)
  }
  return out
}

/** The public view of a plan, from form values: what the landing page card is drawn from. */
export function formToPublicPlan(values: PlanFormInput) {
  const limits: Record<string, number | null> = {}
  for (const { key } of LIMIT_FIELDS) {
    const text = values.limits[key].trim()
    limits[key] = /^\d+$/.test(text) ? Number(text) : null
  }
  return {
    code: values.code,
    name: values.name || 'Plan name',
    priceMonthly: isMoneyString(values.priceMonthly.trim()) ? values.priceMonthly.trim() : '0',
    priceYearly: isMoneyString(values.priceYearly.trim()) ? values.priceYearly.trim() : '0',
    limits,
    features: { ...values.features },
  }
}
