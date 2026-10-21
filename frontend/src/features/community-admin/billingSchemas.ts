import { z } from 'zod'
import { todayInIst } from '@/lib/dates'
import { compareMoney, formatMoney, isMoneyString, isPositiveMoney } from '@/lib/money'
import type {
  CreateFeePlanRequest,
  CreateInvoiceRequest,
  FeeAudience,
  FeeFrequency,
  FeeKind,
  FeePlanView,
  MoneyString,
  PaymentMethod,
  RecordPaymentRequest,
  UpdateFeePlanRequest,
} from '@/types'

const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a date')

/** A positive amount with at most two decimals, read as a string: never converted to a number. */
const positiveAmount = (empty: string) =>
  z
    .string()
    .trim()
    .min(1, empty)
    .refine((value) => isMoneyString(value) && isPositiveMoney(value), {
      message: 'Enter an amount greater than zero, like 1500 or 1500.50',
    })

// ---- recording a payment ----------------------------------------------------------------------

export const PAYMENT_METHODS: ReadonlyArray<{ value: PaymentMethod; label: string }> = [
  { value: 'UPI', label: 'UPI' },
  { value: 'CASH', label: 'Cash' },
  { value: 'BANK', label: 'Bank transfer' },
  { value: 'CHEQUE', label: 'Cheque' },
  { value: 'OTHER', label: 'Other' },
]

/**
 * A payment against one invoice. It may be partial but never more than what is still owed: the
 * server refuses an overpayment too (422 OVERPAYMENT), this just says so before sending.
 */
export function recordPaymentSchema(balance: MoneyString, currency = 'INR') {
  return z.object({
    amount: positiveAmount('Enter the amount received').refine(
      (value) => !isMoneyString(value) || compareMoney(value, balance) <= 0,
      {
        message: `That is more than the balance of ${formatMoney(balance, { currency })}. Enter the balance or less.`,
      },
    ),
    method: z.enum(['UPI', 'CASH', 'BANK', 'CHEQUE', 'OTHER']),
    reference: z.string().trim().max(100, 'Use 100 characters or fewer'),
    receivedOn: date.refine((value) => value <= todayInIst(), {
      message: 'The payment cannot be dated in the future',
    }),
  })
}
export type RecordPaymentInput = z.input<ReturnType<typeof recordPaymentSchema>>
export type RecordPaymentValues = z.output<ReturnType<typeof recordPaymentSchema>>

export function toRecordPaymentRequest(
  values: RecordPaymentValues,
  invoiceVersion: number,
): RecordPaymentRequest {
  return {
    amount: values.amount,
    method: values.method,
    receivedOn: values.receivedOn,
    expectedVersion: invoiceVersion,
    ...(values.reference ? { reference: values.reference } : {}),
  }
}

// ---- fee plans --------------------------------------------------------------------------------

export const FEE_KINDS: ReadonlyArray<{ value: FeeKind; label: string }> = [
  { value: 'MAINTENANCE', label: 'Maintenance' },
  { value: 'SUBSCRIPTION', label: 'Subscription' },
  { value: 'DONATION', label: 'Donation' },
  { value: 'EVENT', label: 'Event' },
  { value: 'FINE', label: 'Fine' },
  { value: 'OTHER', label: 'Other' },
]
export const FEE_FREQUENCIES: ReadonlyArray<{ value: FeeFrequency; label: string }> = [
  { value: 'MONTHLY', label: 'Every month' },
  { value: 'QUARTERLY', label: 'Every quarter' },
  { value: 'YEARLY', label: 'Every year' },
  { value: 'ONE_TIME', label: 'Once' },
]
export const FEE_AUDIENCES: ReadonlyArray<{ value: FeeAudience; label: string }> = [
  { value: 'ALL_ACTIVE', label: 'Every active member' },
  { value: 'GROUP', label: 'One group' },
  { value: 'SELECTED', label: 'Members I choose' },
]

export const feePlanSchema = z
  .object({
    name: z.string().trim().min(1, 'Enter a name').max(150, 'Use 150 characters or fewer'),
    kind: z.enum(['MAINTENANCE', 'SUBSCRIPTION', 'DONATION', 'EVENT', 'FINE', 'OTHER']),
    amount: positiveAmount('Enter the amount each member pays'),
    frequency: z.enum(['ONE_TIME', 'MONTHLY', 'QUARTERLY', 'YEARLY']),
    dueDay: z.union([
      z.literal(''),
      z
        .string()
        .regex(/^\d{1,2}$/, 'Enter a day from 1 to 28')
        .refine((value) => Number(value) >= 1 && Number(value) <= 28, {
          message: 'Enter a day from 1 to 28',
        }),
    ]),
    appliesTo: z.enum(['ALL_ACTIVE', 'GROUP', 'SELECTED']),
    group: z.string().trim().max(100, 'Use 100 characters or fewer'),
    memberIds: z.array(z.string()),
    autoGenerate: z.boolean(),
    active: z.boolean(),
  })
  .superRefine((value, context) => {
    if (value.appliesTo === 'GROUP' && value.group === '') {
      context.addIssue({ code: 'custom', path: ['group'], message: 'Enter the group to bill' })
    }
    if (value.appliesTo === 'SELECTED' && value.memberIds.length === 0) {
      context.addIssue({
        code: 'custom',
        path: ['memberIds'],
        message: 'Choose at least one member',
      })
    }
    if (value.autoGenerate && value.frequency === 'ONE_TIME') {
      context.addIssue({
        code: 'custom',
        path: ['autoGenerate'],
        message: 'A plan that is billed once cannot be billed automatically',
      })
    }
  })
export type FeePlanInput = z.input<typeof feePlanSchema>
export type FeePlanValues = z.output<typeof feePlanSchema>

export const EMPTY_FEE_PLAN: FeePlanInput = {
  name: '',
  kind: 'MAINTENANCE',
  amount: '',
  frequency: 'MONTHLY',
  dueDay: '10',
  appliesTo: 'ALL_ACTIVE',
  group: '',
  memberIds: [],
  autoGenerate: false,
  active: true,
}

export function feePlanToForm(plan: FeePlanView): FeePlanInput {
  return {
    name: plan.name,
    // An old plan of the retired MEMBERSHIP kind is shown as Other: the kind is never sent on edit.
    kind: plan.kind === 'MEMBERSHIP' ? 'OTHER' : plan.kind,
    amount: plan.amount,
    frequency: plan.frequency,
    dueDay: plan.dueDay === null ? '' : String(plan.dueDay),
    appliesTo: plan.appliesTo,
    group: plan.group ?? '',
    memberIds: plan.memberIds,
    autoGenerate: plan.autoGenerate,
    active: plan.active,
  }
}

function audienceFields(values: FeePlanValues) {
  return {
    appliesTo: values.appliesTo,
    ...(values.appliesTo === 'GROUP' ? { group: values.group } : {}),
    ...(values.appliesTo === 'SELECTED' ? { memberIds: values.memberIds } : {}),
  }
}

export function toCreateFeePlanRequest(values: FeePlanValues): CreateFeePlanRequest {
  return {
    name: values.name,
    kind: values.kind,
    amount: values.amount,
    frequency: values.frequency,
    autoGenerate: values.autoGenerate,
    ...(values.dueDay ? { dueDay: Number(values.dueDay) } : {}),
    ...audienceFields(values),
  }
}

/** The kind and frequency never change: they define what a period means. */
export function toUpdateFeePlanRequest(values: FeePlanValues): UpdateFeePlanRequest {
  return {
    name: values.name,
    amount: values.amount,
    autoGenerate: values.autoGenerate,
    active: values.active,
    ...(values.dueDay ? { dueDay: Number(values.dueDay) } : {}),
    ...audienceFields(values),
  }
}

// ---- generating invoices ----------------------------------------------------------------------

/** What a billing run needs: which plan, and for a one-time plan also the period and due date. */
export function generateSchema(oneTime: boolean) {
  return z.object({
    feePlanId: z.string().min(1, 'Choose a fee plan'),
    period: oneTime
      ? z
          .string()
          .trim()
          .min(1, 'Name this billing, for example Annual day 2026')
          .max(30, 'Use 30 characters or fewer')
      : z.string().trim().max(30, 'Use 30 characters or fewer'),
    dueDate: oneTime ? date : z.union([z.literal(''), date]),
    sendEmails: z.boolean(),
  })
}
export type GenerateInput = z.input<ReturnType<typeof generateSchema>>

// ---- a one-off invoice ------------------------------------------------------------------------

export const newInvoiceSchema = z.object({
  memberId: z.string().min(1, 'Choose a member'),
  kind: z.enum(['MAINTENANCE', 'SUBSCRIPTION', 'DONATION', 'EVENT', 'FINE', 'OTHER']),
  description: z
    .string()
    .trim()
    .min(1, 'Describe what it is for')
    .max(200, 'Use 200 characters or fewer'),
  amount: positiveAmount('Enter the amount'),
  dueDate: date,
  period: z.string().trim().max(30, 'Use 30 characters or fewer'),
  sendEmail: z.boolean(),
})
export type NewInvoiceInput = z.input<typeof newInvoiceSchema>
export type NewInvoiceValues = z.output<typeof newInvoiceSchema>

export function emptyInvoice(): NewInvoiceInput {
  return {
    memberId: '',
    kind: 'MAINTENANCE',
    description: '',
    amount: '',
    dueDate: todayInIst(),
    period: '',
    sendEmail: true,
  }
}

export function toCreateInvoiceRequest(values: NewInvoiceValues): CreateInvoiceRequest {
  return {
    memberId: values.memberId,
    kind: values.kind,
    description: values.description,
    amount: values.amount,
    dueDate: values.dueDate,
    sendEmail: values.sendEmail,
    ...(values.period ? { period: values.period } : {}),
  }
}
