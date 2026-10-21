import { act, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { authStore } from '@/store/authStore'
import { resetSession, setSession } from '@/test/fixtures'
import { GuestOnlyRoute } from './GuestOnlyRoute'
import { ProtectedRoute } from './ProtectedRoute'
import { paths } from './paths'

function Where() {
  const location = useLocation()
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname
  return (
    <p data-testid="where">
      {location.pathname}
      {from ? ` (from ${from})` : ''}
    </p>
  )
}

function renderApp(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path={paths.login} element={<Where />} />
        <Route path={paths.unauthorized} element={<Where />} />
        <Route path={paths.twoFactorSetup} element={<Where />} />
        <Route element={<ProtectedRoute roles={['SUPER_ADMIN']} />}>
          <Route path="/superadmin/dashboard" element={<p>super secret</p>} />
        </Route>
        <Route element={<ProtectedRoute />}>
          <Route path="/any-role" element={<p>any role page</p>} />
        </Route>
        <Route element={<ProtectedRoute allowMfaSetup />}>
          <Route path="/mfa-ok" element={<p>mfa ok page</p>} />
        </Route>
        <Route element={<GuestOnlyRoute />}>
          <Route path="/guest" element={<p>guest page</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// The guards ask for the session to be restored while it is initializing; tests set the state directly.
vi.mock('@/lib/session', () => ({ bootstrapSession: vi.fn(() => Promise.resolve()) }))

afterEach(resetSession)

describe('ProtectedRoute', () => {
  it('shows a spinner, not the login page, while the session is restored', () => {
    resetSession() // initializing
    renderApp('/superadmin/dashboard')
    expect(screen.getByRole('status')).toHaveTextContent('Checking your session')
    expect(screen.queryByText('super secret')).not.toBeInTheDocument()
    expect(screen.queryByTestId('where')).not.toBeInTheDocument()
  })

  it('then renders the page once the session is known', () => {
    setSession('SUPER_ADMIN')
    renderApp('/superadmin/dashboard')
    expect(screen.getByText('super secret')).toBeInTheDocument()
  })

  it('redirects a signed-out visitor to /login and remembers where they were going', () => {
    setSession(null)
    renderApp('/superadmin/dashboard')
    expect(screen.getByTestId('where')).toHaveTextContent('/login (from /superadmin/dashboard)')
  })

  it('sends the wrong role to /unauthorized', () => {
    setSession('COMMUNITY_ADMIN')
    renderApp('/superadmin/dashboard')
    expect(screen.getByTestId('where')).toHaveTextContent('/unauthorized')
    expect(screen.queryByText('super secret')).not.toBeInTheDocument()
  })

  it('lets any signed-in role through when no roles are required', () => {
    setSession('COMMUNITY_ADMIN')
    renderApp('/any-role')
    expect(screen.getByText('any role page')).toBeInTheDocument()
  })

  it('forces the 2FA setup page when the token only allows setup', () => {
    setSession('SUPER_ADMIN', { mfaSetupRequired: true })
    renderApp('/superadmin/dashboard')
    expect(screen.getByTestId('where')).toHaveTextContent('/2fa/setup')
  })

  it('lets the setup route itself through', () => {
    setSession('SUPER_ADMIN', { mfaSetupRequired: true })
    renderApp('/mfa-ok')
    expect(screen.getByText('mfa ok page')).toBeInTheDocument()
  })

  it('reacts live when the API says setup is required mid-session', () => {
    setSession('COMMUNITY_ADMIN')
    renderApp('/any-role')
    expect(screen.getByText('any role page')).toBeInTheDocument()
    act(() => authStore.getState().setMfaSetupRequired(true)) // what the axios interceptor does on a 403
    expect(screen.getByTestId('where')).toHaveTextContent('/2fa/setup')
  })
})

describe('GuestOnlyRoute', () => {
  it('shows guest pages to signed-out visitors', () => {
    setSession(null)
    renderApp('/guest')
    expect(screen.getByText('guest page')).toBeInTheDocument()
  })

  it('moves a signed-in user to their home', () => {
    setSession('SUPER_ADMIN')
    renderApp('/guest')
    // /superadmin/dashboard is the super admin home; it renders the protected page here.
    expect(screen.getByText('super secret')).toBeInTheDocument()
  })
})
