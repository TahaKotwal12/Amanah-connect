import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Gauge, LifeBuoy, Megaphone, Settings, Users, Wallet } from 'lucide-react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { authStore } from '@/store/authStore'
import { expectNoA11yViolations } from '@/test/a11y'
import { resetSession, setSession } from '@/test/fixtures'
import { installMockApi } from '@/test/mockApi'
import { clearViewportMock, mockViewport } from '@/test/render'
import { AppShell } from './AppShell'
import type { NavItem } from './nav'

const nav: NavItem[] = [
  { to: '/admin/dashboard', label: 'Dashboard', icon: Gauge },
  { to: '/admin/members', label: 'Members', icon: Users },
  { to: '/admin/billing', label: 'Billing', icon: Wallet },
  { to: '/admin/announcements', label: 'Announcements', icon: Megaphone },
  { to: '/admin/support', label: 'Support', icon: LifeBuoy },
  { to: '/admin/settings', label: 'Settings', icon: Settings },
]

function renderShell(path = '/admin/members') {
  const router = createMemoryRouter(
    [
      {
        element: (
          <AppShell
            subtitle="Community"
            nav={nav}
            supportPath="/admin/support"
            settingsPath="/admin/settings"
            notificationCount={3}
          />
        ),
        children: [
          ...nav.map((item) => ({ path: item.to, element: <h1>{item.label} page</h1> })),
          { path: '/login', element: <h1>Login page</h1> },
        ],
      },
    ],
    { initialEntries: [path] },
  )
  const view = render(<RouterProvider router={router} />)
  return { router, ...view }
}

beforeEach(() => {
  localStorage.clear()
  setSession('COMMUNITY_ADMIN')
})
afterEach(() => {
  clearViewportMock()
  resetSession()
})

describe('AppShell on a wide screen', () => {
  it('shows the sidebar with the current page marked, the community in the top bar, and passes axe', async () => {
    const { container } = renderShell()
    const main = screen.getByRole('navigation', { name: 'Main' })
    expect(within(main).getAllByRole('link')).toHaveLength(nav.length)
    expect(within(main).getByRole('link', { name: 'Members' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(main).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute(
      'aria-current',
    )
    expect(screen.getByText('Test Community')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Members page' })).toBeInTheDocument()
    await expectNoA11yViolations(container)
  })

  it('collapses to icons, keeps the names available, and remembers the choice', async () => {
    const user = userEvent.setup()
    const first = renderShell()
    await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }))
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
    expect(screen.getByRole('link', { name: 'Billing' })).toBeInTheDocument() // still named for screen readers
    expect(localStorage.getItem('amanah.sidebar-collapsed')).toBe('1')
    first.unmount()

    renderShell()
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeInTheDocument()
  })

  it('opens the user menu, and signing out clears the session and goes to /login', async () => {
    const user = userEvent.setup()
    const mock = installMockApi({ 'POST /auth/logout': () => ({ status: 204 }) })
    const { router } = renderShell()
    await user.click(screen.getByRole('button', { name: 'Account menu for Community Admin' }))
    expect(screen.getByText('admin@example.test')).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Settings' })).toHaveAttribute(
      'href',
      '/admin/settings',
    )
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }))
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login')
    })
    expect(authStore.getState().user).toBeNull()
    expect(mock.count('POST /auth/logout')).toBe(1)
    mock.restore()
  })

  it('shows the unread count and offers help that leads to Support', async () => {
    const user = userEvent.setup()
    const { router } = renderShell()
    expect(screen.getByRole('button', { name: 'Notifications, 3 unread' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Help' }))
    expect(screen.getByRole('dialog', { name: 'Need help?' })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Go to Support' }))
    expect(router.state.location.pathname).toBe('/admin/support')
  })
})

describe('AppShell on a phone', () => {
  it('swaps the sidebar for a bottom bar with four items and a More sheet', async () => {
    mockViewport(false)
    const user = userEvent.setup()
    const { container, router } = renderShell()
    const bar = screen.getByRole('navigation', { name: 'Main' })
    expect(within(bar).getAllByRole('link')).toHaveLength(4)
    expect(screen.queryByRole('button', { name: 'Collapse sidebar' })).not.toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(within(bar).getByRole('button', { name: 'More' }))
    const sheet = screen.getByRole('dialog', { name: 'Menu' })
    expect(within(sheet).getAllByRole('link')).toHaveLength(nav.length)
    await user.click(within(sheet).getByRole('link', { name: 'Settings' }))
    expect(router.state.location.pathname).toBe('/admin/settings')
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: 'Menu' })).not.toBeInTheDocument()
    })
  })
})
