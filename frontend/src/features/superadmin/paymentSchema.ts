import { addYears, format, parseISO, subDays } from 'date-fns'
import { z } from 'zod'
import { isMoneyString } from '@/lib/money'
import type { IsoDate } from '@/types'

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a date')

/** Mirrors RecordSubscriptionRequest on the backend. */
export const recordPaymentSchema = z
  .object({
    communityId: z.string().min(1, 'Choose a community'),
    planId: z.string().min(1, 'Choose a plan'),
    amount: z
      .string()
      .trim()
      .min(1, 'Enter the amount received (0 if it was free)')
      .refine((value) => isMoneyString(value) && !value.startsWith('-'), {
        message: 'Enter an amount like 4990 or 4990.50',
      }),
    reference: z.string().trim().max(100, 'Use 100 characters or fewer'),
    periodStart: isoDate,
    periodEnd: isoDate,
    paidOn: z.union([isoDate, z.literal('')]),
  })
  .refine((values) => values.periodEnd >= values.periodStart, {
    path: ['periodEnd'],
    message: 'The period must end on or after the day it starts',
  })

export type RecordPaymentInput = z.input<typeof recordPaymentSchema>
export type RecordPaymentValues = z.output<typeof recordPaymentSchema>

/** One year of service from `start`: the last day is the day before the anniversary. */
export function defaultPeriodEnd(start: IsoDate): IsoDate {
  return format(subDays(addYears(parseISO(start), 1), 1), 'yyyy-MM-dd')
}
