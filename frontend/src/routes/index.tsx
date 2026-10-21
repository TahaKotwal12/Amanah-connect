import { Navigate, createBrowserRouter, type RouteObject } from 'react-router'
import NotFoundPage from '@/shared/components/NotFoundPage'
import { PageSpinner } from '@/shared/components/PageSpinner'
import { RouteError } from '@/shared/components/RouteError'
import { RootLayout } from '@/shared/components/layout/RootLayout'
import { GuestOnlyRoute } from './GuestOnlyRoute'
import { ProtectedRoute } from './ProtectedRoute'
import { lazyPage } from './lazyPage'

/**
 * Every page is its own lazy chunk (lazyPage), so the first paint only downloads the shell and the
 * page being opened. Relative `path`s are used inside the superadmin and admin trees.
 */
export const appRoutes: RouteObject[] = [
  {
    element: <RootLayout />,
    errorElement: <RouteError />,
    HydrateFallback: () => <PageSpinner fullScreen />,
    children: [
      // ---- marketing site: its own navbar and footer ----
      {
        lazy: lazyPage(() => import('@/features/landing/MarketingLayout')),
        children: [
          { index: true, lazy: lazyPage(() => import('@/features/landing/pages/LandingPage')) },
          {
            path: 'privacy',
            lazy: lazyPage(() => import('@/features/landing/pages/PrivacyPolicyPage')),
          },
          { path: 'terms', lazy: lazyPage(() => import('@/features/landing/pages/TermsPage')) },
        ],
      },

      // ---- sign-in pages: split-screen layout ----
      {
        lazy: lazyPage(() => import('@/features/auth/AuthLayout')),
        children: [
          {
            element: <GuestOnlyRoute />,
            children: [
              { path: 'login', lazy: lazyPage(() => import('@/features/auth/pages/LoginPage')) },
            ],
          },
          {
            path: 'login/2fa',
            lazy: lazyPage(() => import('@/features/auth/pages/TwoFactorChallengePage')),
          },
          {
            path: 'forgot-password',
            lazy: lazyPage(() => import('@/features/auth/pages/ForgotPasswordPage')),
          },
          {
            path: 'reset-password',
            lazy: lazyPage(() => import('@/features/auth/pages/ResetPasswordPage')),
          },
          {
            path: 'accept-invite',
            lazy: lazyPage(() => import('@/features/auth/pages/AcceptInvitePage')),
          },
          {
            path: 'unauthorized',
            lazy: lazyPage(() => import('@/features/auth/pages/UnauthorizedPage')),
          },
        ],
      },

      // ---- pages members reach from an emailed link or a QR code ----
      {
        lazy: lazyPage(() => import('@/features/community-admin/public/MemberLayout')),
        children: [
          // Member-facing pages: no login, the token in the link is the credential.
          {
            path: 'join/:token',
            lazy: lazyPage(() => import('@/features/community-admin/public/JoinPage')),
          },
          {
            path: 'pay/:token',
            lazy: lazyPage(() => import('@/features/community-admin/public/PayPage')),
          },

          { path: '*', element: <NotFoundPage /> },
        ],
      },

      // ---- dev-only component showcase: not in the production bundle ----
      ...(import.meta.env.DEV
        ? [
            {
              path: 'styleguide',
              lazy: lazyPage(() => import('@/features/styleguide/StyleguidePage')),
            },
          ]
        : []),

      // ---- signed in, but the token may still only be good for 2FA setup ----
      {
        element: <ProtectedRoute allowMfaSetup />,
        children: [
          {
            lazy: lazyPage(() => import('@/features/auth/AuthLayout')),
            children: [
              {
                path: '2fa/setup',
                lazy: lazyPage(() => import('@/features/auth/pages/TwoFactorSetupPage')),
              },
            ],
          },
        ],
      },

      // ---- super admin ----
      {
        path: 'superadmin',
        element: <ProtectedRoute roles={['SUPER_ADMIN']} />,
        children: [
          {
            lazy: lazyPage(() => import('./SuperAdminShell')),
            children: [
              { index: true, element: <Navigate to="dashboard" replace /> },
              {
                path: 'dashboard',
                lazy: lazyPage(() => import('@/features/superadmin/pages/DashboardPage')),
              },
              {
                path: 'communities',
                lazy: lazyPage(() => import('@/features/superadmin/pages/CommunitiesPage')),
              },
              {
                path: 'communities/:id',
                lazy: lazyPage(() => import('@/features/superadmin/pages/CommunityDetailPage')),
              },
              {
                path: 'communities/:id/support-view',
                lazy: lazyPage(
                  () => import('@/features/superadmin/pages/CommunitySupportViewPage'),
                ),
              },
              {
                path: 'plans',
                lazy: lazyPage(() => import('@/features/superadmin/pages/PlansPage')),
              },
              {
                path: 'subscriptions',
                lazy: lazyPage(() => import('@/features/superadmin/pages/SubscriptionsPage')),
              },
              {
                path: 'invites',
                lazy: lazyPage(() => import('@/features/superadmin/pages/InvitesPage')),
              },
              {
                path: 'leads',
                lazy: lazyPage(() => import('@/features/superadmin/pages/LeadsPage')),
              },
              {
                path: 'support',
                lazy: lazyPage(() => import('@/features/superadmin/pages/SupportPage')),
              },
              {
                path: 'announcements',
                lazy: lazyPage(() => import('@/features/superadmin/pages/AnnouncementsPage')),
              },
              {
                path: 'audit',
                lazy: lazyPage(() => import('@/features/superadmin/pages/AuditPage')),
              },
              { path: '*', element: <NotFoundPage /> },
            ],
          },
        ],
      },

      // ---- community admin ----
      {
        path: 'admin',
        element: <ProtectedRoute roles={['COMMUNITY_ADMIN']} />,
        children: [
          {
            lazy: lazyPage(() => import('./CommunityAdminShell')),
            children: [
              { index: true, element: <Navigate to="dashboard" replace /> },
              {
                path: 'dashboard',
                lazy: lazyPage(() => import('@/features/community-admin/pages/DashboardPage')),
              },
              {
                path: 'members',
                lazy: lazyPage(() => import('@/features/community-admin/pages/MembersPage')),
              },
              {
                path: 'members/:id',
                lazy: lazyPage(() => import('@/features/community-admin/pages/MemberDetailPage')),
              },
              {
                path: 'invites',
                lazy: lazyPage(() => import('@/features/community-admin/pages/InvitesPage')),
              },
              {
                path: 'billing',
                lazy: lazyPage(() => import('@/features/community-admin/pages/BillingPage')),
              },
              {
                path: 'billing/invoices/:id',
                lazy: lazyPage(() => import('@/features/community-admin/pages/InvoiceDetailPage')),
              },
              {
                path: 'budget',
                lazy: lazyPage(() => import('@/features/community-admin/pages/BudgetPage')),
              },
              {
                path: 'complaints',
                lazy: lazyPage(() => import('@/features/community-admin/pages/ComplaintsPage')),
              },
              {
                path: 'announcements',
                lazy: lazyPage(() => import('@/features/community-admin/pages/AnnouncementsPage')),
              },
              {
                path: 'support',
                lazy: lazyPage(() => import('@/features/community-admin/pages/SupportPage')),
              },
              {
                path: 'settings',
                lazy: lazyPage(() => import('@/features/community-admin/pages/SettingsPage')),
              },
              { path: '*', element: <NotFoundPage /> },
            ],
          },
        ],
      },
    ],
  },
]

export function createAppRouter() {
  return createBrowserRouter(appRoutes)
}
