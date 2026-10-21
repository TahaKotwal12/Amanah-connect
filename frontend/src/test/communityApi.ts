import { problem, type MockHandler } from './mockApi'

const page = { items: [], total: 0, page: 0, size: 20 }
const ok =
  (data: unknown): MockHandler =>
  () => ({ status: 200, data })

/** An empty but valid community: enough for any community admin page to load. */
export function emptyCommunityApi(): Record<string, MockHandler> {
  return {
    'GET /community/plan': ok({
      planCode: 'STARTER',
      planName: 'Starter',
      limits: { max_members: 50 },
      features: {},
      usage: { members: 0, emailsThisMonth: 0 },
      communityStatus: 'ACTIVE',
      subscription: null,
      expiringWindowDays: 30,
    }),
    'GET /community/notifications/summary': ok({ unread: 0, banners: [] }),
    'GET /community/notifications': ok(page),
    'GET /community/settings': ok({
      name: 'Test Community',
      logoUrl: null,
      currency: 'INR',
      memberGroupLabel: 'Group',
    }),
    'GET /community/dashboard': ok({
      generatedAt: '2026-10-18T10:00:00Z',
      currency: 'INR',
      month: '2026-10',
      members: { total: 0, active: 0 },
      collection: {
        billed: '0.00',
        collected: '0.00',
        outstanding: '0.00',
        receivedInMonth: '0.00',
      },
      overdue: { count: 0, amount: '0.00' },
      netBalance: '0.00',
      complaints: { open: 0, slaBreached: 0 },
      unreadSupportMessages: 0,
      upcomingDues: { days: 7, count: 0, amount: '0.00', items: [] },
      recentActivity: [],
      collectionTrend: [],
    }),
    'GET /community/invoices': ok(page),
    'GET /community/invoices/totals': ok({
      count: 0,
      amount: '0.00',
      amountPaid: '0.00',
      balance: '0.00',
    }),
    'GET /community/fee-plans': ok([]),
    'GET /community/receipts': ok(page),
    'GET /community/reminders/defaulters': ok(page),
    'GET /community/ledger/entries': ok(page),
    'GET /community/ledger/categories': ok([]),
    'GET /community/ledger/summary': ok({
      from: '2026-04-01',
      to: '2027-03-31',
      openingBalance: '0.00',
      totalIncome: '0.00',
      totalExpense: '0.00',
      net: '0.00',
      closingBalance: '0.00',
      byCategory: [],
      byMonth: [],
    }),
    'GET /community/complaints': ok(page),
    'GET /community/complaints/counts': ok({
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
    }),
    'GET /community/complaints/assignees': ok([]),
    'GET /community/announcements': ok(page),
    'GET /community/support/threads': ok(page),
    'GET /community/support/summary': ok({
      unreadMessages: 0,
      unreadThreads: 0,
      activeThreads: 0,
      threads: [],
    }),
    'GET /community/members': ok(page),
    'GET /community/members/counts': ok({
      total: 0,
      active: 0,
      inactive: 0,
      newThisMonth: 0,
      pendingRegistrations: 0,
    }),
    'GET /community/member-invites': ok(page),
    'GET /community/registrations': ok(page),
    // Public pages: a link nobody knows.
    'GET /public/invites/abc123': () => problem(404, 'INVITE_UNAVAILABLE'),
    'GET /public/pay/abc123': () => problem(404, 'PAY_LINK_UNAVAILABLE'),
  }
}
