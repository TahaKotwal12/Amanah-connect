import { resourceKeys } from '@/lib/queryKeys'
import type { LeadStatus, PageParams, ThreadStatus, Uuid } from '@/types'

const scope = 'superadmin'

export interface CommunityListParams extends PageParams {
  q?: string
  status?: string
  /** A plan code, e.g. STARTER. */
  plan?: string
}
export interface LeadListParams extends PageParams {
  q?: string
  status?: LeadStatus
}
export interface InviteListParams extends PageParams {
  status?: string
  q?: string
}
export interface AnnouncementListParams extends PageParams {
  status?: string
}
export interface SubscriptionListParams extends PageParams {
  /** ACTIVE, EXPIRING, EXPIRED or CANCELLED. */
  status?: string
  expiringInDays?: number
  communityId?: Uuid
}
export interface SupportListParams extends PageParams {
  /** Repeatable: more than one status can be asked for. */
  status?: ThreadStatus[]
  priority?: string
  communityId?: Uuid
  /** Only threads nobody has picked up. */
  unassigned?: boolean
  /** Only threads assigned to the signed-in person. */
  mine?: boolean
  /** Only threads with customer messages nobody has read yet. */
  unread?: boolean
  q?: string
}
export interface AuditParams {
  actor?: Uuid
  communityId?: Uuid
  action?: string
  from?: string
  to?: string
  cursor?: string
  limit?: number
}

export const superadminKeys = {
  stats: {
    all: [scope, 'stats'] as const,
    summary: () => [scope, 'stats', 'summary'] as const,
    monthly: (months: number) => [scope, 'stats', 'monthly', months] as const,
  },
  communities: {
    ...resourceKeys<CommunityListParams>(scope, 'communities'),
    overview: (id: Uuid) => [scope, 'communities', 'detail', id, 'overview'] as const,
    subscriptions: (id: Uuid) => [scope, 'communities', 'detail', id, 'subscriptions'] as const,
    activity: (id: Uuid) => [scope, 'communities', 'detail', id, 'activity'] as const,
    importReport: (batchId: Uuid) => [scope, 'communities', 'import', batchId] as const,
  },
  plans: resourceKeys(scope, 'plans'),
  subscriptions: resourceKeys<SubscriptionListParams>(scope, 'subscriptions'),
  invites: resourceKeys<InviteListParams>(scope, 'invites'),
  leads: resourceKeys<LeadListParams>(scope, 'leads'),
  support: {
    ...resourceKeys<SupportListParams>(scope, 'support'),
    messages: (threadId: Uuid) => [scope, 'support', 'detail', threadId, 'messages'] as const,
    summary: () => [scope, 'support', 'summary'] as const,
    counts: () => [scope, 'support', 'counts'] as const,
  },
  announcements: {
    ...resourceKeys<AnnouncementListParams>(scope, 'announcements'),
    preview: (id: Uuid) => [scope, 'announcements', 'detail', id, 'preview'] as const,
  },
  audit: {
    all: [scope, 'audit'] as const,
    list: (params: AuditParams) => [scope, 'audit', 'list', params] as const,
  },
}
