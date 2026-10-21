import type { MockHandler } from './mockApi'

const page = { items: [], total: 0, page: 0, size: 20 }
const ok =
  (data: unknown): MockHandler =>
  () => ({ status: 200, data })

/**
 * An empty but valid platform: enough for any super admin page to load, so tests about routing and
 * layout do not each have to mock the data underneath.
 */
export function emptySuperadminApi(): Record<string, MockHandler> {
  return {
    'GET /admin/stats': ok({
      communities: { total: 0, pending: 0, active: 0, suspended: 0, archived: 0 },
      totalMembers: 0,
      platformRevenue: { thisMonth: '0.00', thisYear: '0.00', currency: 'INR' },
      expiringSubscriptions: 0,
      expiringWithinDays: 30,
      newLeads: 0,
      openSupportThreads: 0,
    }),
    'GET /admin/stats/monthly': ok({ currency: 'INR', months: [] }),
    'GET /admin/plans': ok([]),
    'GET /admin/subscriptions': ok(page),
    'GET /admin/subscriptions/expiring': ok(page),
    'GET /admin/leads': ok(page),
    'GET /admin/communities': ok(page),
  }
}
