import type {
  IsoDate,
  IsoInstant,
  LedgerSource,
  LedgerType,
  MoneyString,
  UploadInstructions,
  Uuid,
} from './common'

export interface CategoryView {
  id: Uuid
  name: string
  type: LedgerType
  active: boolean
  system: boolean
  entryCount: number
}
export interface CreateCategoryRequest {
  name: string
  type: LedgerType
}
export interface UpdateCategoryRequest {
  name?: string
  active?: boolean
}
export interface CategoryRef {
  id: Uuid
  name: string
}
export interface EntryView {
  id: Uuid
  type: LedgerType
  category: CategoryRef
  amount: MoneyString
  entryDate: IsoDate
  title: string
  notes: string | null
  attachmentKey: string | null
  attachmentUrl: string | null
  source: LedgerSource
  sourceId: Uuid | null
  reversedOf: Uuid | null
  reversalReason: string | null
  reversed: boolean
  /** Payment-linked and reversal rows cannot be edited. */
  readOnly: boolean
  createdAt: IsoInstant
}
export interface CreateEntryRequest {
  type: LedgerType
  categoryId: Uuid
  amount: MoneyString
  entryDate: IsoDate
  title: string
  notes?: string
  attachmentKey?: string
}
export interface UpdateEntryRequest {
  categoryId?: Uuid
  entryDate?: IsoDate
  title?: string
  notes?: string
  attachmentKey?: string
}
export interface ReverseEntryRequest {
  reason: string
  entryDate?: IsoDate
}
export interface LedgerUploadView extends UploadInstructions {
  attachmentKey: string
}
export interface CategoryTotal {
  categoryId: Uuid
  name: string
  type: LedgerType
  total: MoneyString
}
export interface MonthTotal {
  /** yyyy-MM */
  month: string
  income: MoneyString
  expense: MoneyString
  net: MoneyString
}
export interface LedgerSummaryView {
  from: IsoDate
  to: IsoDate
  openingBalance: MoneyString
  totalIncome: MoneyString
  totalExpense: MoneyString
  net: MoneyString
  closingBalance: MoneyString
  byCategory: CategoryTotal[]
  byMonth: MonthTotal[]
}
