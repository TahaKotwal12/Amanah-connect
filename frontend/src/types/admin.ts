// Super admin: communities, plans, subscriptions, leads, imports, audit. Plus community settings and
// the public lead form.
import type {
  CursorPage,
  IsoDate,
  IsoInstant,
  JsonObject,
  LeadStatus,
  MoneyString,
  UploadInstructions,
  Uuid,
} from './common'

// ---- communities ------------------------------------------------------------------------------

export interface CreateCommunityRequest {
  name: string
  /** Lower case letters, digits and hyphens. Generated when omitted. */
  slug?: string
  contactName?: string
  contactEmail?: string
  contactPhone?: string
  addressLine1?: string
  addressLine2?: string
  city?: string
  state?: string
  postalCode?: string
  /** ISO 3166 alpha-2, e.g. IN. */
  country?: string
  dateOfEstablishment?: IsoDate
  planId: Uuid
  /** 1 to 12. */
  financialYearStartMonth?: number
  ownerName: string
  ownerEmail: string
  leadId?: Uuid
}
export interface UpdateCommunityRequest {
  name?: string
  contactName?: string
  contactEmail?: string
  contactPhone?: string
  addressLine1?: string
  addressLine2?: string
  city?: string
  state?: string
  postalCode?: string
  country?: string
  dateOfEstablishment?: IsoDate
  planId?: Uuid
  require2fa?: boolean
  /** Optimistic locking: send the version you loaded. */
  version?: number
}
export interface StatusChangeRequest {
  reason: string
}
export interface OptionalReasonRequest {
  reason?: string
}
export interface ResetAdminPasswordRequest {
  userId?: Uuid
}
export interface PlanRef {
  id: Uuid
  code: string
  name: string
}
export interface OwnerView {
  id: Uuid
  fullName: string
  email: string
  status: string
}
export interface CommunityView {
  id: Uuid
  name: string
  slug: string
  status: string
  statusReason: string | null
  statusChangedAt: IsoInstant | null
  contactName: string | null
  contactEmail: string | null
  contactPhone: string | null
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  dateOfEstablishment: IsoDate | null
  plan: PlanRef
  currency: string
  financialYearStartMonth: number
  require2fa: boolean
  owner: OwnerView | null
  memberCount: number
  subscriptionEndsOn: IsoDate | null
  subscriptionStatus: string | null
  version: number
  createdAt: IsoInstant
}
export interface CommunityListItem {
  id: Uuid
  name: string
  slug: string
  status: string
  plan: PlanRef
  memberCount: number
  subscriptionEndsOn: IsoDate | null
  subscriptionStatus: string | null
  ownerName: string | null
  ownerEmail: string | null
  createdAt: IsoInstant
}
export interface SubscriptionLine {
  id: Uuid
  planCode: string
  planName: string
  periodStart: IsoDate
  periodEnd: IsoDate
  amount: MoneyString
  paidOn: IsoDate | null
  reference: string | null
  status: string
}
export interface CommunityExport {
  exportedAt: IsoInstant
  profile: CommunityView
  membersCount: number
  subscriptionHistory: SubscriptionLine[]
}
export interface ActivityLine {
  id: Uuid
  action: string
  entityType: string | null
  entityId: Uuid | null
  actorUserId: Uuid | null
  actorName: string | null
  at: IsoInstant
}
export interface CommunityCounts {
  membersActive: number
  membersInactive: number
  pendingRegistrations: number
  openInvoices: number
  openComplaints: number
  openSupportThreads: number
}
export interface FinanceSummary {
  invoicedOpen: MoneyString
  collectedOnOpenAndPaid: MoneyString
  outstanding: MoneyString
  incomeThisFinancialYear: MoneyString
  expenseThisFinancialYear: MoneyString
  netThisFinancialYear: MoneyString
  financialYearStart: IsoDate
}
export interface CommunityOverview {
  community: CommunityView
  counts: CommunityCounts
  finance: FinanceSummary
  recentActivity: ActivityLine[]
}

// ---- plans and subscriptions ------------------------------------------------------------------

export interface CreatePlanRequest {
  /** Upper case letters, digits and underscores. */
  code: string
  name: string
  priceMonthly: MoneyString
  priceYearly: MoneyString
  limits?: JsonObject
  features?: JsonObject
  publicPlan?: boolean
  active?: boolean
  sortOrder?: number
}
export interface UpdatePlanRequest {
  name?: string
  priceMonthly?: MoneyString
  priceYearly?: MoneyString
  limits?: JsonObject
  features?: JsonObject
  publicPlan?: boolean
  active?: boolean
  sortOrder?: number
}
export interface PlanView {
  id: Uuid
  code: string
  name: string
  priceMonthly: MoneyString
  priceYearly: MoneyString
  limits: JsonObject
  features: JsonObject
  publicPlan: boolean
  active: boolean
  sortOrder: number
  communityCount: number
  createdAt: IsoInstant
}
export interface RecordSubscriptionRequest {
  communityId: Uuid
  planId: Uuid
  amount: MoneyString
  reference?: string
  periodStart: IsoDate
  periodEnd: IsoDate
  paidOn?: IsoDate
}
export interface CancelSubscriptionRequest {
  reason: string
}
export interface SubscriptionView {
  id: Uuid
  communityId: Uuid
  communityName: string
  communitySlug: string
  planCode: string
  planName: string
  periodStart: IsoDate
  periodEnd: IsoDate
  amount: MoneyString
  paidOn: IsoDate | null
  reference: string | null
  status: string
  cancelReason: string | null
  daysLeft: number
  createdAt: IsoInstant
}

// ---- leads ------------------------------------------------------------------------------------

export interface PublicLeadRequest {
  name: string
  email: string
  phone?: string
  communityName?: string
  /** Approximate member count, 1 to 10,000,000. */
  size?: number
  message?: string
  /** Honeypot: must stay empty. */
  website?: string
}
export interface PublicLeadResponse {
  received: boolean
}
export interface LeadView {
  id: Uuid
  name: string
  email: string
  phone: string | null
  communityName: string | null
  size: number | null
  message: string | null
  status: LeadStatus
  handledBy: Uuid | null
  convertedCommunityId: Uuid | null
  source: string | null
  createdAt: IsoInstant
  updatedAt: IsoInstant
}
export interface UpdateLeadStatusRequest {
  status: LeadStatus
}
export interface CommunityDraft {
  request: CreateCommunityRequest
  suggestedPlanCode: string | null
}

// ---- community settings -----------------------------------------------------------------------

export interface NotificationSettingsView {
  dueReminderDaysBefore: number
  overdueReminderEveryDays: number
  sendWelcomeEmail: boolean
  sendReceiptEmail: boolean
}
export interface SettingsView {
  name: string
  contactName: string | null
  contactEmail: string | null
  contactPhone: string | null
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  dateOfEstablishment: IsoDate | null
  logoKey: string | null
  logoUrl: string | null
  currency: string
  financialYearStartMonth: number
  financialSettingsLocked: boolean
  upiId: string | null
  upiPayeeName: string | null
  memberGroupLabel: string
  openingBalance: MoneyString
  notifications: NotificationSettingsView
  version: number
}
export interface UpdateSettingsRequest {
  name?: string
  contactName?: string
  contactEmail?: string
  contactPhone?: string
  addressLine1?: string
  addressLine2?: string
  city?: string
  state?: string
  postalCode?: string
  country?: string
  dateOfEstablishment?: IsoDate
  logoKey?: string
  currency?: string
  financialYearStartMonth?: number
  upiId?: string
  upiPayeeName?: string
  memberGroupLabel?: string
  openingBalance?: MoneyString
  notifications?: Partial<NotificationSettingsView>
  version?: number
}
export interface LogoUploadView extends UploadInstructions {
  logoKey: string
}

// ---- imports ----------------------------------------------------------------------------------

export interface ImportRowResult {
  row: number
  key: string
  errors: string[]
}
export interface ImportFileReport {
  provided: boolean
  totalRows: number
  validRows: number
  invalidRows: number
  errors: string[]
  warnings: string[]
  valid: ImportRowResult[]
  invalid: ImportRowResult[]
}
export interface ImportReport {
  batchId: Uuid
  status: string
  confirmable: boolean
  summary: {
    validRows: number
    invalidRows: number
    newMembers: number
    planMemberLimit: number | null
    currentMembers: number
  }
  members: ImportFileReport
  openingBalances: ImportFileReport
  openInvoices: ImportFileReport
  blockingErrors: string[]
  warnings: string[]
  result: JsonObject | null
}
export interface ConfirmImportRequest {
  skipInvalidRows?: boolean
}

// ---- audit ------------------------------------------------------------------------------------

export interface AdminAuditEntry {
  id: Uuid
  at: IsoInstant
  action: string
  summary: string
  actorId: Uuid | null
  actorName: string | null
  actorRole: string | null
  communityId: Uuid | null
  communityName: string | null
  entityType: string | null
  entityId: Uuid | null
  before: JsonObject | null
  after: JsonObject | null
  ip: string | null
  userAgent: string | null
  requestId: string | null
}
export interface CommunityAuditEntry {
  id: Uuid
  at: IsoInstant
  action: string
  summary: string
  actorName: string
  byPlatformStaff: boolean
  entityType: string | null
  entityId: Uuid | null
  before: JsonObject | null
  after: JsonObject | null
}
export type AdminAuditPage = CursorPage<AdminAuditEntry>
export type CommunityAuditPage = CursorPage<CommunityAuditEntry>

// ---- platform dashboard -----------------------------------------------------------------------

export interface PlatformStats {
  communities: {
    total: number
    pending: number
    active: number
    suspended: number
    archived: number
  }
  totalMembers: number
  platformRevenue: { thisMonth: MoneyString; thisYear: MoneyString; currency: string }
  expiringSubscriptions: number
  expiringWithinDays: number
  newLeads: number
  openSupportThreads: number
}
/** One calendar month (IST). `month` is yyyy-MM. */
export interface MonthlyPoint {
  month: string
  revenue: MoneyString
  newCommunities: number
}
export interface MonthlySeries {
  currency: string
  /** Oldest first, months with nothing included as zero. */
  months: MonthlyPoint[]
}

// ---- administrator invitations ----------------------------------------------------------------

export type InviteStatus = 'PENDING' | 'ACCEPTED' | 'EXPIRED' | 'REVOKED'
/** One invited administrator, from their latest invitation. */
export interface InviteRow {
  userId: Uuid
  email: string
  fullName: string
  communityId: Uuid | null
  communityName: string | null
  status: InviteStatus
  invitedAt: IsoInstant
  expiresAt: IsoInstant
  acceptedAt: IsoInstant | null
}
