import type {
  IsoDate,
  IsoInstant,
  JsonObject,
  MemberStatus,
  MoneyString,
  RegistrationStatus,
  Uuid,
} from './common'

export interface MemberView {
  id: Uuid
  memberNo: string
  fullName: string
  email: string | null
  phone: string | null
  group: string | null
  status: MemberStatus
  statusReason: string | null
  statusChangedAt: IsoInstant | null
  joinedOn: IsoDate | null
  customFields: JsonObject | null
  consentEmail: boolean
  createdAt: IsoInstant
  updatedAt: IsoInstant
}

export interface CreateMemberRequest {
  fullName: string
  email?: string
  phone?: string
  group?: string
  joinedOn?: IsoDate
  customFields?: JsonObject
  consentEmail?: boolean
  allowDuplicateEmail?: boolean
}
export type UpdateMemberRequest = Partial<CreateMemberRequest>
export interface DeactivateRequest {
  reason: string
}
export interface ActivateRequest {
  reason?: string
}
export interface MemberCounts {
  total: number
  active: number
  inactive: number
  newThisMonth: number
  pendingRegistrations: number
}
export interface UnpaidSummary {
  invoices: number
  outstanding: MoneyString
}
export interface MemberFilter {
  q?: string
  status?: MemberStatus
  group?: string
}

// ---- invites and registrations ----------------------------------------------------------------

export interface CreateInviteRequest {
  /** 1 to 90. */
  expiresInDays?: number
  /** 1 to 1000. */
  maxUses?: number
  defaultGroup?: string
}
export interface InviteByEmailRequest {
  email: string
  name?: string
  expiresInDays?: number
  defaultGroup?: string
}
export interface InviteView {
  id: Uuid
  state: string
  expiresAt: IsoInstant
  maxUses: number
  usedCount: number
  defaultGroup: string | null
  invitedEmail: string | null
  revokedAt: IsoInstant | null
  createdAt: IsoInstant
}
export interface CreatedInviteView {
  invite: InviteView
  link: string
  qrCodePngBase64: string
}
export interface EmailedInviteView {
  invite: InviteView
}
export interface RegistrationView {
  id: Uuid
  fullName: string
  email: string
  phone: string | null
  group: string | null
  consentEmail: boolean
  status: RegistrationStatus
  createdAt: IsoInstant
  reviewedAt: IsoInstant | null
  rejectionReason: string | null
  memberId: Uuid | null
  memberNo: string | null
  emailUsedByMemberNo: string | null
}
export interface ApproveRegistrationRequest {
  allowDuplicateEmail?: boolean
  group?: string
}
export interface RejectRegistrationRequest {
  reason: string
}

// ---- public join page (/join/:token) ----------------------------------------------------------

export interface FormField {
  name: string
  label: string
  type: string
  required: boolean
  maxLength: number | null
}
export interface PublicInviteView {
  communityName: string
  logoUrl: string | null
  groupLabel: string
  defaultGroup: string | null
  fields: FormField[]
}
export interface PublicRegisterRequest {
  fullName: string
  email: string
  phone?: string
  group?: string
  consentEmail?: boolean
  /** Honeypot: must stay empty. */
  website?: string
}
export interface PublicRegisterResponse {
  received: boolean
}

// ---- the community's own plan and usage (GET /community/plan) ----------------------------------

export interface CommunityPlanView {
  planCode: string
  planName: string
  /** A null limit means unlimited. */
  limits: Record<string, number | null>
  features: Record<string, boolean>
  usage: { members: number; emailsThisMonth: number }
  /** PENDING, ACTIVE, SUSPENDED or ARCHIVED. SUSPENDED and ARCHIVED are read only. */
  communityStatus: string
  /** The latest paid period that is not cancelled, or null when nothing was ever recorded. */
  subscription: {
    status: 'ACTIVE' | 'EXPIRING' | 'EXPIRED'
    periodStart: IsoDate
    periodEnd: IsoDate
    daysLeft: number
  } | null
  expiringWindowDays: number
}

/** One email the system sent (or tried to send) to a member. */
export interface MemberCommunication {
  id: Uuid
  /** The internal template name, e.g. member-welcome. */
  template: string
  status: 'PENDING' | 'SENT' | 'FAILED'
  createdAt: IsoInstant
  sentAt: IsoInstant | null
}
