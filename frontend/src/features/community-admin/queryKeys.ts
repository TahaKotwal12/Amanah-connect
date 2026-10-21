import { resourceKeys } from '@/lib/queryKeys'
import type {
  AnnouncementStatus,
  ComplaintStatus,
  InvoiceStatus,
  IsoDate,
  LedgerType,
  MemberFilter,
  PageParams,
  Priority,
  RegistrationStatus,
  ThreadStatus,
  ReportKind,
  Uuid,
} from '@/types'

const scope = 'community-admin'

export interface MemberListParams extends PageParams, MemberFilter {}
export interface InvoiceListParams extends PageParams {
  status?: InvoiceStatus
  memberId?: Uuid
  q?: string
  dueFrom?: IsoDate
  dueTo?: IsoDate
  /** Only what is still owed. */
  outstandingOnly?: boolean
}
/** The invoice list's filters, without paging: also what the totals are asked for. */
export type InvoiceFilters = Omit<InvoiceListParams, 'page' | 'size' | 'sort'>
export interface ReceiptListParams extends PageParams {
  from?: IsoDate
  to?: IsoDate
  q?: string
  reversed?: boolean
}
export interface LedgerListParams extends PageParams {
  type?: LedgerType
  categoryId?: Uuid
  from?: IsoDate
  to?: IsoDate
  /** MANUAL or PAYMENT. */
  source?: string
  q?: string
}
export interface InviteListParams extends PageParams {
  /** ALL, ACTIVE, EXPIRED, USED_UP or REVOKED. */
  state?: string
}
export interface RegistrationListParams extends PageParams {
  status?: RegistrationStatus
}
export interface DateRange {
  from?: IsoDate
  to?: IsoDate
}
export interface ComplaintListParams extends PageParams {
  status?: ComplaintStatus | ComplaintStatus[]
  priority?: Priority
  category?: string
  memberId?: Uuid
  assignedTo?: Uuid
  unassigned?: boolean
  mine?: boolean
  slaBreached?: boolean
  q?: string
}

export interface SupportListParams extends PageParams {
  status?: ThreadStatus[]
  unread?: boolean
  q?: string
}
export interface AnnouncementListParams extends PageParams {
  status?: AnnouncementStatus
}

export const communityAdminKeys = {
  dashboard: () => [scope, 'dashboard'] as const,
  plan: () => [scope, 'plan'] as const,
  notifications: () => [scope, 'notifications'] as const,
  notificationSummary: () => [scope, 'notifications', 'summary'] as const,

  members: {
    ...resourceKeys<MemberListParams>(scope, 'members'),
    counts: () => [scope, 'members', 'counts'] as const,
    statement: (id: Uuid) => [scope, 'members', 'detail', id, 'statement'] as const,
    payments: (id: Uuid) => [scope, 'members', 'detail', id, 'payments'] as const,
    complaints: (id: Uuid) => [scope, 'members', 'detail', id, 'complaints'] as const,
    communications: (id: Uuid) => [scope, 'members', 'detail', id, 'communications'] as const,
  },
  invites: resourceKeys<InviteListParams>(scope, 'invites'),
  registrations: resourceKeys<RegistrationListParams>(scope, 'registrations'),

  feePlans: resourceKeys<PageParams & { active?: boolean }>(scope, 'fee-plans'),
  invoices: {
    ...resourceKeys<InvoiceListParams>(scope, 'invoices'),
    totals: (filters: InvoiceFilters) => [scope, 'invoices', 'totals', filters] as const,
  },
  payments: resourceKeys<PageParams>(scope, 'payments'),
  receipts: resourceKeys<ReceiptListParams>(scope, 'receipts'),
  defaulters: resourceKeys<PageParams>(scope, 'defaulters'),

  ledger: {
    ...resourceKeys<LedgerListParams>(scope, 'ledger'),
    summary: (range: DateRange) => [scope, 'ledger', 'summary', range] as const,
    categories: () => [scope, 'ledger', 'categories'] as const,
  },
  reports: {
    all: [scope, 'reports'] as const,
    preview: (kind: ReportKind, range: DateRange) => [scope, 'reports', kind, range] as const,
  },

  complaints: {
    ...resourceKeys<ComplaintListParams>(scope, 'complaints'),
    counts: () => [scope, 'complaints', 'counts'] as const,
    assignees: () => [scope, 'complaints', 'assignees'] as const,
    comments: (id: Uuid) => [scope, 'complaints', 'detail', id, 'comments'] as const,
  },
  announcements: {
    ...resourceKeys<AnnouncementListParams>(scope, 'announcements'),
    preview: (id: Uuid) => [scope, 'announcements', 'detail', id, 'preview'] as const,
  },
  support: {
    ...resourceKeys<SupportListParams>(scope, 'support'),
    messages: (threadId: Uuid) => [scope, 'support', 'detail', threadId, 'messages'] as const,
    summary: () => [scope, 'support', 'summary'] as const,
  },
  settings: () => [scope, 'settings'] as const,
  audit: {
    all: [scope, 'audit'] as const,
    list: (params: { action?: string; cursor?: string }) =>
      [scope, 'audit', 'list', params] as const,
  },
  dataExports: () => [scope, 'data-exports'] as const,

  /** Public pages: no scope prefix needed, they never share cache with the admin app. */
  publicInvite: (token: string) => ['public', 'invite', token] as const,
  publicPay: (token: string) => ['public', 'pay', token] as const,
}
