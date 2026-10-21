import { z } from 'zod'
import { todayInIst } from '@/lib/dates'
import { isMoneyString, isPositiveMoney } from '@/lib/money'
import type {
  CategoryView,
  CreateEntryRequest,
  EntryView,
  LedgerType,
  UpdateEntryRequest,
} from '@/types'

const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a date')

export const LEDGER_TYPES: ReadonlyArray<{ value: LedgerType; label: string }> = [
  { value: 'INCOME', label: 'Income' },
  { value: 'EXPENSE', label: 'Expense' },
]

/** Mirrors CreateEntryRequest on the backend. The amount is a string and stays one. */
export const entrySchema = z.object({
  type: z.enum(['INCOME', 'EXPENSE']),
  categoryId: z.string().min(1, 'Choose a category'),
  amount: z
    .string()
    .trim()
    .min(1, 'Enter the amount')
    .refine((value) => isMoneyString(value) && isPositiveMoney(value), {
      message: 'Enter an amount greater than zero, like 1500 or 1500.50',
    }),
  entryDate: date.refine((value) => value <= todayInIst(), {
    message: 'The entry cannot be dated in the future',
  }),
  title: z.string().trim().min(1, 'Enter a title').max(200, 'Use 200 characters or fewer'),
  notes: z.string().trim().max(2000, 'Use 2,000 characters or fewer'),
  /** The key of an uploaded attachment; empty for none. */
  attachmentKey: z.string(),
})
export type EntryInput = z.input<typeof entrySchema>
export type EntryValues = z.output<typeof entrySchema>

export function emptyEntry(type: LedgerType): EntryInput {
  return {
    type,
    categoryId: '',
    amount: '',
    entryDate: todayInIst(),
    title: '',
    notes: '',
    attachmentKey: '',
  }
}

export function entryToForm(entry: EntryView): EntryInput {
  return {
    type: entry.type,
    categoryId: entry.category.id,
    amount: entry.amount,
    entryDate: entry.entryDate,
    title: entry.title,
    notes: entry.notes ?? '',
    attachmentKey: entry.attachmentKey ?? '',
  }
}

export function toCreateEntryRequest(values: EntryValues): CreateEntryRequest {
  return {
    type: values.type,
    categoryId: values.categoryId,
    amount: values.amount,
    entryDate: values.entryDate,
    title: values.title,
    ...(values.notes ? { notes: values.notes } : {}),
    ...(values.attachmentKey ? { attachmentKey: values.attachmentKey } : {}),
  }
}

/** The amount and type never change. An empty attachment key removes the attachment. */
export function toUpdateEntryRequest(
  values: EntryValues,
  original: EntryInput,
): UpdateEntryRequest {
  const body: UpdateEntryRequest = {}
  if (values.categoryId !== original.categoryId) body.categoryId = values.categoryId
  if (values.entryDate !== original.entryDate) body.entryDate = values.entryDate
  if (values.title !== original.title) body.title = values.title
  if (values.notes !== original.notes) body.notes = values.notes
  if (values.attachmentKey !== original.attachmentKey) body.attachmentKey = values.attachmentKey
  return body
}

/** Categories an entry of this type may use: active ones of the same type. */
export function categoriesFor(
  categories: readonly CategoryView[],
  type: LedgerType,
): CategoryView[] {
  return categories.filter((category) => category.type === type && category.active)
}

export const categorySchema = z.object({
  name: z.string().trim().min(1, 'Enter a name').max(100, 'Use 100 characters or fewer'),
  type: z.enum(['INCOME', 'EXPENSE']),
})
export type CategoryInput = z.input<typeof categorySchema>
