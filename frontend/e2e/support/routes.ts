import type { Role } from './fakeApi.ts'

/** Every route the app serves, with the role that can open it. */
interface RouteCase {
  name: string
  path: string
  role: Role
}

const adminPages = [
  'dashboard',
  'members',
  'members/m1',
  'invites',
  'billing',
  'budget',
  'complaints',
  'announcements',
  'support',
  'settings',
]
const superAdminPages = [
  'dashboard',
  'communities',
  'communities/com-1',
  'plans',
  'subscriptions',
  'invites',
  'leads',
  'support',
  'announcements',
  'audit',
]

export const ROUTES: RouteCase[] = [
  // public
  { name: 'landing', path: '/', role: 'GUEST' },
  { name: 'privacy', path: '/privacy', role: 'GUEST' },
  { name: 'terms', path: '/terms', role: 'GUEST' },
  { name: 'not-found', path: '/nothing-here', role: 'GUEST' },
  // sign-in pages
  { name: 'login', path: '/login', role: 'GUEST' },
  { name: 'forgot-password', path: '/forgot-password', role: 'GUEST' },
  { name: 'reset-password', path: '/reset-password?token=abc', role: 'GUEST' },
  { name: 'accept-invite', path: '/accept-invite?token=abc', role: 'GUEST' },
  { name: 'unauthorized', path: '/unauthorized', role: 'GUEST' },
  // member pages reached from a link
  { name: 'join-unavailable', path: '/join/abc123', role: 'GUEST' },
  { name: 'pay-unavailable', path: '/pay/abc123', role: 'GUEST' },
  ...superAdminPages.map((p) => ({
    name: `superadmin-${p.replace('/', '-')}`,
    path: `/superadmin/${p}`,
    role: 'SUPER_ADMIN' as const,
  })),
  ...adminPages.map((p) => ({
    name: `admin-${p.replace('/', '-')}`,
    path: `/admin/${p}`,
    role: 'COMMUNITY_ADMIN' as const,
  })),
]
