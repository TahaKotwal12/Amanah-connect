import type { Page, Route } from '@playwright/test'

/**
 * A small stand-in for the API, shared by the accessibility sweep, the responsive checks and the
 * smoke suite. It answers every read the pages make with valid, mostly empty data (plus a few rows
 * where a populated table matters), so each page renders its real layout without a backend.
 * Writes are accepted and ignored.
 */
const page0 = <T>(items: T[]) => ({ items, total: items.length, page: 0, size: 20 })
const reply = (route: Route, body: unknown, status = 200) => route.fulfill({ status, json: body })

export const SUPER_ADMIN_ME = {
  user: {
    id: '3f2b8c1e-6d7a-4c58-9a55-2d1a0e8c9b11',
    email: 'root@example.test',
    fullName: 'Platform Admin',
    role: 'SUPER_ADMIN',
    status: 'ACTIVE',
  },
  community: null,
  flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: true },
}

export const COMMUNITY_ADMIN_ME = {
  user: {
    id: '7a1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d',
    email: 'admin@example.test',
    fullName: 'Community Admin',
    role: 'COMMUNITY_ADMIN',
    status: 'ACTIVE',
  },
  community: { id: 'c1', name: 'Lotus Residents', slug: 'lotus', status: 'ACTIVE' },
  flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: false },
}

const PLAN = {
  id: 'plan-1',
  code: 'STARTER',
  name: 'Starter',
  priceMonthly: '499.00',
  priceYearly: '4990.00',
  limits: { max_members: 50 },
  features: {},
  publicPlan: true,
  active: true,
  sortOrder: 1,
  communityCount: 1,
  createdAt: '2026-01-01T00:00:00Z',
}

const MEMBER = {
  id: 'm1',
  memberNo: 'LR-0001',
  fullName: 'Asha Rao',
  email: 'asha@example.test',
  phone: null,
  group: 'Block A',
  status: 'ACTIVE',
  statusReason: null,
  statusChangedAt: null,
  joinedOn: null,
  customFields: null,
  consentEmail: true,
  createdAt: '2026-10-18T10:00:00Z',
  updatedAt: '2026-10-18T10:00:00Z',
}

const COMMUNITY_ROW = {
  id: 'com-1',
  name: 'Lotus Residents',
  slug: 'lotus',
  status: 'ACTIVE',
  plan: { id: PLAN.id, code: PLAN.code, name: PLAN.name },
  memberCount: 42,
  subscriptionEndsOn: '2026-12-01',
  subscriptionStatus: 'ACTIVE',
  ownerName: 'Asha Rao',
  ownerEmail: 'asha@example.test',
  createdAt: '2026-10-17T10:00:00Z',
}

const EMPTY_SUPPORT_SUMMARY = { unreadMessages: 0, unreadThreads: 0, activeThreads: 0, threads: [] }

/** Answers for GET /admin/... (super admin). */
function adminRead(path: string): unknown {
  switch (path) {
    case '/plans':
      return [PLAN]
    case '/communities':
      return page0([COMMUNITY_ROW])
    case '/stats':
      return {
        communities: { total: 12, pending: 1, active: 9, suspended: 1, archived: 1 },
        totalMembers: 840,
        platformRevenue: { thisMonth: '24990.00', thisYear: '212000.00', currency: 'INR' },
        expiringSubscriptions: 2,
        expiringWithinDays: 30,
        newLeads: 3,
        openSupportThreads: 1,
      }
    case '/stats/monthly':
      return {
        currency: 'INR',
        months: ['2026-08', '2026-09', '2026-10'].map((month, i) => ({
          month,
          revenue: `${(i + 1) * 5000}.00`,
          newCommunities: i + 1,
        })),
      }
    case '/support/counts':
      return {
        total: 0,
        open: 0,
        waiting: 0,
        resolved: 0,
        closed: 0,
        activeUnassigned: 0,
        urgentActive: 0,
        unreadThreads: 0,
      }
    case '/support/summary':
      return EMPTY_SUPPORT_SUMMARY
    default:
      return page0([])
  }
}

/** Answers for GET /community/... (community admin). */
function communityRead(path: string): unknown {
  switch (path) {
    case '/plan':
      return {
        planCode: 'STARTER',
        planName: 'Starter',
        limits: { max_members: 50 },
        features: {},
        usage: { members: 1, emailsThisMonth: 0 },
        communityStatus: 'ACTIVE',
        subscription: null,
        expiringWindowDays: 30,
      }
    case '/notifications/summary':
      return { unread: 0, banners: [] }
    case '/settings':
      return { name: 'Lotus Residents', logoUrl: null, currency: 'INR', memberGroupLabel: 'Block' }
    case '/dashboard':
      return {
        generatedAt: '2026-10-18T10:00:00Z',
        currency: 'INR',
        month: '2026-10',
        members: { total: 1, active: 1 },
        collection: {
          billed: '12000.00',
          collected: '9000.00',
          outstanding: '3000.00',
          receivedInMonth: '9000.00',
        },
        overdue: { count: 0, amount: '0.00' },
        netBalance: '9000.00',
        complaints: { open: 0, slaBreached: 0 },
        unreadSupportMessages: 0,
        upcomingDues: { days: 7, count: 0, amount: '0.00', items: [] },
        recentActivity: [],
        collectionTrend: [],
      }
    case '/members':
      return page0([MEMBER])
    case '/members/counts':
      return { total: 1, active: 1, inactive: 0, newThisMonth: 0, pendingRegistrations: 0 }
    case '/invoices/totals':
      return { count: 0, amount: '0.00', amountPaid: '0.00', balance: '0.00' }
    case '/fee-plans':
    case '/ledger/categories':
    case '/complaints/assignees':
      return []
    case '/ledger/summary':
      return {
        from: '2026-04-01',
        to: '2027-03-31',
        openingBalance: '0.00',
        totalIncome: '0.00',
        totalExpense: '0.00',
        net: '0.00',
        closingBalance: '0.00',
        byCategory: [],
        byMonth: [],
      }
    case '/complaints/counts':
      return {
        total: 0,
        open: 0,
        inProgress: 0,
        resolved: 0,
        closed: 0,
        active: 0,
        slaBreached: 0,
        urgentActive: 0,
        unassignedActive: 0,
        assignedToMe: 0,
        byCategory: {},
        byPriority: {},
      }
    case '/support/summary':
      return EMPTY_SUPPORT_SUMMARY
    default:
      return page0([])
  }
}

export type Role = 'SUPER_ADMIN' | 'COMMUNITY_ADMIN' | 'GUEST'

export interface FakeReply {
  status: number
  body: unknown
}

const problem = (status: number, code: string, detail: string): FakeReply => ({
  status,
  body: { status, code, detail },
})

/** The answer to one API call (`path` is everything after /api/v1). Pure, so a real HTTP server can use it too. */
export function respond(role: Role, method: string, path: string): FakeReply {
  if (path === '/public/plans') return { status: 200, body: [] }
  if (path.startsWith('/public/')) return problem(404, 'NOT_FOUND', 'Not found')
  if (path === '/auth/refresh') {
    return role === 'GUEST'
      ? problem(401, 'INVALID_REFRESH_TOKEN', 'Invalid refresh token')
      : {
          status: 200,
          body: {
            accessToken: 'e2e',
            tokenType: 'Bearer',
            expiresIn: 900,
            mfaSetupRequired: false,
          },
        }
  }
  if (role === 'GUEST') return problem(401, 'UNAUTHENTICATED', 'Sign in')
  if (path === '/auth/me') {
    return { status: 200, body: role === 'SUPER_ADMIN' ? SUPER_ADMIN_ME : COMMUNITY_ADMIN_ME }
  }
  const prefix = role === 'SUPER_ADMIN' ? '/admin' : '/community'
  if (!path.startsWith(`${prefix}/`)) return problem(403, 'FORBIDDEN', 'Not allowed')
  if (method !== 'GET') return { status: 200, body: {} }
  const rest = path.slice(prefix.length)
  return { status: 200, body: role === 'SUPER_ADMIN' ? adminRead(rest) : communityRead(rest) }
}

/** Install the stand-in for one role in a Playwright page. Call before `page.goto`. */
export async function fakeApi(page: Page, role: Role): Promise<void> {
  await page.route('**/api/v1/**', (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/v1', '')
    const { status, body } = respond(role, request.method(), path)
    return reply(route, body, status)
  })
}
