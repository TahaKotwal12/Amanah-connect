import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { bootstrapSession } from '@/lib/session'
import { PageSpinner } from '@/shared/components/PageSpinner'
import { selectIsAuthenticated, useAuthStore } from '@/store/authStore'
import type { UserRole } from '@/types'
import { paths } from './paths'

interface ProtectedRouteProps {
  /** Roles allowed in. Omit to allow any signed-in user. */
  roles?: readonly UserRole[]
  /** Let a user whose token still needs 2FA setup through. Only the setup page sets this. */
  allowMfaSetup?: boolean
}

/**
 * Route guard, used as a layout route: <Route element={<ProtectedRoute roles={['SUPER_ADMIN']} />}>.
 *
 *  1. Wait for the session bootstrap, so a reload does not flash the login page.
 *  2. Signed out: go to /login and remember where the user was headed.
 *  3. Token only allows 2FA setup: force the setup page (every other API call would be refused).
 *  4. Wrong role: /unauthorized. The API enforces roles too; this only keeps people off screens
 *     that could not work for them.
 */
export function ProtectedRoute({ roles, allowMfaSetup = false }: ProtectedRouteProps) {
  const location = useLocation()
  const isInitializing = useAuthStore((state) => state.isInitializing)
  const isAuthenticated = useAuthStore(selectIsAuthenticated)
  const role = useAuthStore((state) => state.user?.role)
  const mfaSetupRequired = useAuthStore((state) => state.mfaSetupRequired)

  // The marketing site never needs a session, so it is restored only once a guarded page asks.
  useEffect(() => {
    if (isInitializing) void bootstrapSession()
  }, [isInitializing])

  if (isInitializing) return <PageSpinner label="Checking your session" fullScreen />

  if (!isAuthenticated || !role) {
    return <Navigate to={paths.login} replace state={{ from: location }} />
  }

  if (mfaSetupRequired && !allowMfaSetup) {
    return <Navigate to={paths.twoFactorSetup} replace />
  }

  if (roles && !roles.includes(role)) {
    return <Navigate to={paths.unauthorized} replace />
  }

  return <Outlet />
}
