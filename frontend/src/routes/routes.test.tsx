import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { authStore } from '@/store/authStore'
import { expectNoA11yViolations } from '@/test/a11y'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi } from '@/test/mockApi'
import { emptyCommunityApi } from '@/test/communityApi'
import { emptySuperadminApi } from '@/test/superadminApi'
import { appRoutes } from './index'
import { paths } from './paths'

function renderAt(path: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, ...view }
}

let mock: ReturnType<typeof installMockApi> | undefined
beforeEach(() => {
  mock = installMockApi({ ...emptySuperadminApi(), ...emptyCommunityApi() })
})
afterEach(() => {
  cleanup()
  mock?.restore()
  resetSession()
})

/** [path, heading the placeholder renders] for every page, grouped by who may see it. */
const publicPages: Array<[string, string]> = [
  [paths.home, 'Run your community with clarity, care and complete transparency.'],
  [paths.privacy, 'Privacy policy'],
  [paths.terms, 'Terms of service'],
  [paths.login, 'Sign in'],
  [paths.twoFactorChallenge, 'Sign in'], // no challenge in progress: back to the start,
  [paths.forgotPassword, 'Forgot your password?'],
  [paths.resetPassword, 'This link cannot be used'],
  [paths.acceptInvite, 'This invitation cannot be used'],
  [paths.unauthorized, 'You do not have access to that page'],
  [paths.join('abc123'), 'This invitation cannot be used'],
  [paths.pay('abc123'), 'This payment link cannot be used'],
  ['/no/such/page', 'We could not find that page'],
]

const superadminPages: Array<[string, string]> = [
  [paths.superadmin.dashboard, 'Platform dashboard'],
  [paths.superadmin.communities, 'Communities'],
  [paths.superadmin.community('c1'), 'Community'],
  [paths.superadmin.plans, 'Plans'],
  [paths.superadmin.subscriptions, 'Subscriptions'],
  [paths.superadmin.invites, 'Invites'],
  [paths.superadmin.leads, 'Leads'],
  [paths.superadmin.support, 'Support inbox'],
  [paths.superadmin.announcements, 'Platform announcements'],
  [paths.superadmin.audit, 'Audit trail'],
  ['/superadmin/nope', 'We could not find that page'],
]

const adminPages: Array<[string, string]> = [
  [paths.admin.dashboard, 'Dashboard'],
  [paths.admin.members, 'Members'],
  [paths.admin.member('m1'), 'Member'],
  [paths.admin.invites, 'Invites and registrations'],
  [paths.admin.billing, 'Billing'],
  [paths.admin.budget, 'Budget'],
  [paths.admin.complaints, 'Complaints'],
  [paths.admin.announcements, 'Announcements'],
  [paths.admin.support, 'Support'],
  [paths.admin.settings, 'Settings'],
  ['/admin/nope', 'We could not find that page'],
]

describe('every route boots (lazy chunk loads, page renders, no axe violations)', () => {
  it.each(publicPages)('public %s', async (path, heading) => {
    setSession(null)
    const { container } = renderAt(path)
    expect(
      await screen.findByRole('heading', { level: 1, name: heading }, { timeout: 15_000 }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it.each(superadminPages)('super admin %s', async (path, heading) => {
    setSession('SUPER_ADMIN')
    const { container } = renderAt(path)
    expect(
      await screen.findByRole('heading', { level: 1, name: heading }, { timeout: 15_000 }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it.each(adminPages)('community admin %s', async (path, heading) => {
    setSession('COMMUNITY_ADMIN')
    const { container } = renderAt(path)
    expect(
      await screen.findByRole('heading', { level: 1, name: heading }, { timeout: 15_000 }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('shows the 2FA setup page to a signed-in user', async () => {
    const mock = installMockApi({
      'POST /auth/2fa/setup': () => ({
        status: 200,
        data: {
          secret: 'JBSWY3DPEHPK3PXP',
          otpauthUri: 'otpauth://totp/Amanah:a@b.test?secret=JBSWY3DPEHPK3PXP',
        },
      }),
    })
    setSession('SUPER_ADMIN', { mfaSetupRequired: true })
    const router = createMemoryRouter(appRoutes, { initialEntries: [paths.twoFactorSetup] })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    expect(
      await screen.findByRole(
        'heading',
        { level: 1, name: 'Set up two-step verification' },
        { timeout: 15_000 },
      ),
    ).toBeInTheDocument()
    mock.restore()
  })
})

describe('role landing redirects', () => {
  it('sends /superadmin and /admin to their dashboards', async () => {
    setSession('SUPER_ADMIN')
    const first = renderAt(paths.superadmin.root)
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Platform dashboard' }),
    ).toBeInTheDocument()
    expect(first.router.state.location.pathname).toBe(paths.superadmin.dashboard)
    first.unmount()

    setSession('COMMUNITY_ADMIN')
    const second = renderAt(paths.admin.root)
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()
    expect(second.router.state.location.pathname).toBe(paths.admin.dashboard)
  })

  it('keeps a signed-in user off the login page', async () => {
    setSession('COMMUNITY_ADMIN')
    const { router } = renderAt(paths.login)
    expect(await screen.findByRole('heading', { level: 1, name: 'Dashboard' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(paths.admin.dashboard)
  })
})

describe('shell', () => {
  it('has a skip link and a single main landmark', async () => {
    setSession('COMMUNITY_ADMIN')
    renderAt(paths.admin.dashboard)
    await screen.findByRole('heading', { level: 1, name: 'Dashboard' })
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute(
      'href',
      '#main',
    )
    expect(screen.getAllByRole('main')).toHaveLength(1)
  })

  it('uses a real session cleared state, not storage', () => {
    setSession('SUPER_ADMIN')
    expect(authStore.getState().user?.role).toBe('SUPER_ADMIN')
    expect(localStorage).toHaveLength(0)
  })
})
