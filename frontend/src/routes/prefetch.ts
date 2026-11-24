import { paths } from './paths'

/**
 * Start downloading a page's chunk before it is opened. Used on hover and keyboard focus of
 * navigation links, and for the likely next pages once the app is idle after sign-in. A prefetch
 * only fetches the code (the same module the router would load), never data, and a failed one is
 * ignored: the real navigation retries.
 *
 * Keep this in step with the nav lists in SuperAdminShell and CommunityAdminShell (prefetch.test.ts
 * fails when a nav destination has no loader here).
 */
const loaders: Record<string, () => Promise<unknown>> = {
  [paths.admin.dashboard]: () => import('@/features/community-admin/pages/DashboardPage'),
  [paths.admin.members]: () => import('@/features/community-admin/pages/MembersPage'),
  [paths.admin.invites]: () => import('@/features/community-admin/pages/InvitesPage'),
  [paths.admin.billing]: () => import('@/features/community-admin/pages/BillingPage'),
  [paths.admin.budget]: () => import('@/features/community-admin/pages/BudgetPage'),
  [paths.admin.complaints]: () => import('@/features/community-admin/pages/ComplaintsPage'),
  [paths.admin.announcements]: () => import('@/features/community-admin/pages/AnnouncementsPage'),
  [paths.admin.support]: () => import('@/features/community-admin/pages/SupportPage'),
  [paths.admin.settings]: () => import('@/features/community-admin/pages/SettingsPage'),

  [paths.superadmin.dashboard]: () => import('@/features/superadmin/pages/DashboardPage'),
  [paths.superadmin.communities]: () => import('@/features/superadmin/pages/CommunitiesPage'),
  [paths.superadmin.plans]: () => import('@/features/superadmin/pages/PlansPage'),
  [paths.superadmin.subscriptions]: () => import('@/features/superadmin/pages/SubscriptionsPage'),
  [paths.superadmin.invites]: () => import('@/features/superadmin/pages/InvitesPage'),
  [paths.superadmin.leads]: () => import('@/features/superadmin/pages/LeadsPage'),
  [paths.superadmin.support]: () => import('@/features/superadmin/pages/SupportPage'),
  [paths.superadmin.announcements]: () => import('@/features/superadmin/pages/AnnouncementsPage'),
  [paths.superadmin.audit]: () => import('@/features/superadmin/pages/AuditPage'),
}

export function hasPrefetch(path: string): boolean {
  return path in loaders
}

const started = new Set<string>()

export function prefetchRoute(path: string): void {
  const load = loaders[path]
  if (!load || started.has(path)) return
  // Not on a metered or slow connection, or when the person asked for less data.
  const connection = (
    navigator as Navigator & { connection?: { saveData?: boolean; effectiveType?: string } }
  ).connection
  if (connection?.saveData || /(^|-)2g$/.test(connection?.effectiveType ?? '')) return
  started.add(path)
  load().catch(() => {
    started.delete(path)
  })
}

/** When the browser is idle, fetch the next few pages people usually open. */
export function prefetchWhenIdle(paths: readonly string[]): () => void {
  // Tests render one page at a time: loading the others in the background only slows them down.
  if (import.meta.env.MODE === 'test') return () => undefined
  const run = () => {
    for (const path of paths) prefetchRoute(path)
  }
  if ('requestIdleCallback' in window) {
    const id = window.requestIdleCallback(run, { timeout: 5000 })
    return () => {
      window.cancelIdleCallback(id)
    }
  }
  const id = setTimeout(run, 2000)
  return () => {
    clearTimeout(id)
  }
}
