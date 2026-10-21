import type {
  FeeAudience,
  FeeFrequency,
  FeeKind,
  InvoiceStatus,
  IsoDate,
  IsoInstant,
  MoneyString,
  PaymentMethod,
  Uuid,
} from './common'

export interface CreateFeePlanRequest {
  name: string
  kind: FeeKind
  amount: MoneyString
  frequency: FeeFrequency
  /** 1 to 28. */
  dueDay?: number
  appliesTo?: FeeAudience
  group?: string
  memberIds?: Uuid[]
  autoGenerate?: boolean
}
export interface UpdateFeePlanRequest {
  name?: string
  amount?: MoneyString
  dueDay?: number
  appliesTo?: FeeAudience
  group?: string
  memberIds?: Uuid[]
  active?: boolean
  autoGenerate?: boolean
}
export interface FeePlanView {
  id: Uuid
  name: string
  kind: FeeKind
  amount: MoneyString
  frequency: FeeFrequency
  dueDay: number | null
  appliesTo: FeeAudience
  group: string | null
  memberIds: Uuid[]
  active: boolean
  autoGenerate: boolean
  lastGeneratedPeriod: string | null
  currentPeriod: string | null
  createdAt: IsoInstant
}

export interface InvoiceView {
  id: Uuid
  invoiceNo: string
  status: InvoiceStatus
  kind: FeeKind
  memberId: Uuid
  memberNo: string
  memberName: string
  feePlanId: Uuid | null
  period: string | null
  description: string
  amount: MoneyString
  amountPaid: MoneyString
  balance: MoneyString
  issuedOn: IsoDate | null
  dueDate: IsoDate
  cancelReason: string | null
  cancelledAt: IsoInstant | null
  /** Send back as `expectedVersion` when recording a payment. */
  version: number
  createdAt: IsoInstant
}
/** One reminder email queued for an invoice (DUE_SOON or OVERDUE) and the day it was claimed. */
export interface ReminderView {
  kind: string
  on: IsoDate
}
export interface InvoiceDetail {
  invoice: InvoiceView
  payments: PaymentView[]
  reminders: ReminderView[]
}
/** Sums over everything a list filter matches. Cancelled invoices are counted but add nothing. */
export interface InvoiceTotals {
  count: number
  amount: MoneyString
  amountPaid: MoneyString
  balance: MoneyString
}
/** What billing a fee plan for a period would do (POST /invoices/generate/preview). */
export interface GeneratePreview {
  feePlanId: Uuid
  feePlanName: string
  period: string
  dueDate: IsoDate
  eligibleMembers: number
  willCreate: number
  alreadyBilled: number
  skippedInactive: number
  amountEach: MoneyString
  totalToBill: MoneyString
}
/** One member who owes money past its due date (GET /reminders/defaulters). */
export interface DefaulterView {
  memberId: Uuid
  memberNo: string
  fullName: string
  email: string | null
  overdueInvoices: number
  overdueAmount: MoneyString
  oldestDueDate: IsoDate
  lastRemindedOn: IsoDate | null
}
/** The result of "send overdue notices now". */
export interface RemindResult {
  overdueInvoices: number
  queued: number
  alreadyRemindedToday: number
  skipped: number
}
export interface CreateInvoiceRequest {
  memberId: Uuid
  kind: FeeKind
  description: string
  amount: MoneyString
  dueDate: IsoDate
  period?: string
  draft?: boolean
  sendEmail?: boolean
}
export interface UpdateInvoiceRequest {
  kind?: FeeKind
  description?: string
  amount?: MoneyString
  dueDate?: IsoDate
  period?: string
}
export interface IssueInvoiceRequest {
  sendEmail?: boolean
}
export interface CancelInvoiceRequest {
  reason: string
}
export interface GenerateInvoicesRequest {
  feePlanId: Uuid
  period?: string
  dueDate?: IsoDate
  sendEmails?: boolean
}
export interface GenerateResult {
  feePlanId: Uuid
  period: string
  dueDate: IsoDate
  eligibleMembers: number
  created: number
  alreadyBilled: number
  skippedInactive: number
  emailsQueued: number
  emailsSkippedNoAddress: number
  emailsSkippedNoConsent: number
  emailsSkippedQuota: number
  firstInvoiceNo: string | null
  lastInvoiceNo: string | null
}
export interface PayLinkView {
  url: string
  expiresAt: IsoInstant
}

// ---- payments and receipts --------------------------------------------------------------------

/** Send an Idempotency-Key header with this; reuse the same key when retrying. */
export interface RecordPaymentRequest {
  amount: MoneyString
  method: PaymentMethod
  reference?: string
  receivedOn?: IsoDate
  expectedVersion?: number
}
export interface ReversePaymentRequest {
  reason: string
  reversedOn?: IsoDate
}
export interface RecordDonationRequest {
  memberId?: Uuid
  donorName?: string
  amount: MoneyString
  method: PaymentMethod
  reference?: string
  receivedOn?: IsoDate
}
export interface PaymentView {
  id: Uuid
  kind: string
  invoiceId: Uuid | null
  invoiceNo: string | null
  memberId: Uuid | null
  memberNo: string | null
  payerName: string | null
  amount: MoneyString
  method: PaymentMethod
  reference: string | null
  receivedOn: IsoDate
  reversedOf: Uuid | null
  reversalReason: string | null
  reversed: boolean
  receiptId: Uuid | null
  receiptNo: string | null
  createdAt: IsoInstant
}
export interface PaymentResult {
  payment: PaymentView
  invoice: InvoiceView | null
  /** True when the Idempotency-Key was seen before and the earlier result is returned. */
  replayed: boolean
}
export interface ReceiptView {
  id: Uuid
  receiptNo: string
  paymentId: Uuid
  invoiceId: Uuid | null
  invoiceNo: string | null
  payerName: string | null
  amount: MoneyString
  method: PaymentMethod
  reference: string | null
  receivedOn: IsoDate
  reversed: boolean
  reversedOn: IsoDate | null
  reversalReason: string | null
  emailedAt: IsoInstant | null
  createdAt: IsoInstant
}

// ---- public payment page (/pay/:token) --------------------------------------------------------

export interface UpiPayment {
  payeeName: string
  /** upi://pay deep link. */
  link: string
  qrCodePngBase64: string
}
export interface PublicPayView {
  communityName: string
  logoUrl: string | null
  invoiceNo: string
  description: string
  period: string | null
  currency: string
  amount: MoneyString
  amountPaid: MoneyString
  balanceDue: MoneyString
  dueDate: IsoDate
  status: InvoiceStatus
  payable: boolean
  upi: UpiPayment | null
  /** Always true: the community admin confirms payments by hand. */
  manualConfirmation: boolean
  notice: string
}
