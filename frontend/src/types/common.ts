// Hand-mirrored from the backend DTOs (records under backend/src/main/java/com/amanahconnect).
// `npm run gen:api` writes the authoritative, complete version to api.generated.ts. When the two
// disagree, the generated file wins: fix this one.

/** UUID, lower case, 36 chars. */
export type Uuid = string
/** ISO-8601 instant in UTC, e.g. 2026-04-08T10:15:30Z. Display it through lib/dates.ts. */
export type IsoInstant = string
/** ISO-8601 calendar date, e.g. 2026-04-08. A date with no time zone: never wrap it in `new Date()`. */
export type IsoDate = string
/**
 * A money amount: a plain decimal string with at most 2 decimals, e.g. "1500.00" (never a number).
 * Do arithmetic with lib/money.ts only.
 */
export type MoneyString = string
/** Free-form JSON object (custom fields, plan limits and features, audit before/after). */
export type JsonObject = Record<string, unknown>

/** The standard list envelope. `page` is zero-based. */
export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** The standard list parameters: ?page=0&size=20&sort=name,desc */
export interface PageParams {
  page?: number
  /** 1 to 100. Larger values are rejected by the API, not clamped. */
  size?: number
  /** Field names allowed per endpoint, e.g. ['name,desc']. */
  sort?: string[]
}

/** Cursor pagination used by the audit endpoints. */
export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
  hasMore: boolean
}

export interface FieldViolation {
  field: string
  message: string
}

/** RFC 9457 problem+json as the backend sends it (see Problems.java). */
export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  /** Stable error code. Safe to switch on. See lib/errors.ts. */
  code?: string
  requestId?: string
  /**
   * Present on VALIDATION_FAILED (field violations) and on rule failures such as
   * PASSWORD_POLICY_VIOLATION (plain sentences).
   */
  errors?: Array<FieldViolation | string>
}

export interface PingResponse {
  version: string
  time: IsoInstant
}

// ---- enums (string unions: the backend sends the constant name) --------------------------------

export type UserRole = 'SUPER_ADMIN' | 'COMMUNITY_ADMIN'
export type UserStatus = 'INVITED' | 'ACTIVE' | 'DISABLED'
export type CommunityStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED'
export type SubscriptionStatus = 'ACTIVE' | 'EXPIRED' | 'CANCELLED'
export type MemberStatus = 'ACTIVE' | 'INACTIVE'
export type RegistrationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type FeeKind =
  'MAINTENANCE' | 'SUBSCRIPTION' | 'MEMBERSHIP' | 'DONATION' | 'EVENT' | 'FINE' | 'OTHER'
export type FeeFrequency = 'ONE_TIME' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY'
export type FeeAudience = 'ALL_ACTIVE' | 'GROUP' | 'SELECTED'
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PARTIAL' | 'PAID' | 'OVERDUE' | 'CANCELLED'
export type PaymentMethod = 'CASH' | 'UPI' | 'BANK' | 'CHEQUE' | 'OTHER'
export type LedgerType = 'INCOME' | 'EXPENSE'
export type LedgerSource = 'MANUAL' | 'PAYMENT'
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export type ComplaintStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
export type ComplaintVisibility = 'INTERNAL' | 'MEMBER'
export type ThreadStatus = 'OPEN' | 'WAITING' | 'RESOLVED' | 'CLOSED'
export type SupportSide = 'COMMUNITY' | 'PLATFORM'
export type AnnouncementAudience = 'ALL_ACTIVE' | 'GROUP' | 'SELECTED' | 'COMMUNITY_ADMINS'
export type AnnouncementKind = 'ANNOUNCEMENT' | 'OFFER' | 'MAINTENANCE'
export type AnnouncementStatus = 'DRAFT' | 'SCHEDULED' | 'SENT'
export type LeadStatus = 'NEW' | 'CONTACTED' | 'DEMO_SCHEDULED' | 'CONVERTED' | 'LOST'
export type ExportStatus = 'PENDING' | 'RUNNING' | 'READY' | 'FAILED' | 'EXPIRED'
export type ReportKind =
  | 'INCOME_EXPENSE'
  | 'CATEGORY_BREAKDOWN'
  | 'COLLECTION'
  | 'MEMBER_DUES'
  | 'DEFAULTERS'
  | 'RECEIPTS_REGISTER'
export type ReportFormat = 'CSV' | 'PDF'

/** Direct-to-storage upload instructions returned by every "request an upload" endpoint. */
export interface UploadInstructions {
  uploadUrl: string
  method: string
  headers: Record<string, string>
  expiresAt: IsoInstant
  maxBytes: number
}
export interface UploadRequest {
  contentType: string
  sizeBytes: number
}
