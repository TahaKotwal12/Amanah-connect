import type { UserRole } from '@/types'

/** Every path the app links to, in one place so a rename is a one-line change. */
export const paths = {
  home: '/',
  privacy: '/privacy',
  terms: '/terms',

  login: '/login',
  twoFactorChallenge: '/login/2fa',
  forgotPassword: '/forgot-password',
  resetPassword: '/reset-password',
  acceptInvite: '/accept-invite',
  twoFactorSetup: '/2fa/setup',
  unauthorized: '/unauthorized',

  join: (token: string) => `/join/${token}`,
  pay: (token: string) => `/pay/${token}`,

  superadmin: {
    root: '/superadmin',
    dashboard: '/superadmin/dashboard',
    communities: '/superadmin/communities',
    community: (id: string) => `/superadmin/communities/${id}`,
    communitySupportView: (id: string) => `/superadmin/communities/${id}/support-view`,
    plans: '/superadmin/plans',
    subscriptions: '/superadmin/subscriptions',
    invites: '/superadmin/invites',
    leads: '/superadmin/leads',
    support: '/superadmin/support',
    announcements: '/superadmin/announcements',
    audit: '/superadmin/audit',
  },

  admin: {
    root: '/admin',
    dashboard: '/admin/dashboard',
    members: '/admin/members',
    member: (id: string) => `/admin/members/${id}`,
    invites: '/admin/invites',
    billing: '/admin/billing',
    invoice: (id: string) => `/admin/billing/invoices/${id}`,
    budget: '/admin/budget',
    complaints: '/admin/complaints',
    announcements: '/admin/announcements',
    support: '/admin/support',
    settings: '/admin/settings',
  },
} as const

/** Where each role lands after sign-in. */
export function homePathFor(role: UserRole): string {
  return role === 'SUPER_ADMIN' ? paths.superadmin.dashboard : paths.admin.dashboard
}
