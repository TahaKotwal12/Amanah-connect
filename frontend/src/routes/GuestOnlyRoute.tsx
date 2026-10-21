import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { bootstrapSession } from '@/lib/session'
import { destinationAfterLogin, fromPath } from '@/features/auth/loginFlow'
import { PageSpinner } from '@/shared/components/PageSpinner'
import { selectIsAuthenticated, useAuthStore } from '@/store/authStore'

/** Keeps signed-in users off the login page: they go to their own home instead. */
export function GuestOnlyRoute() {
  const location = useLocation()
  const isInitializing = useAuthStore((state) => state.isInitializing)
  const isAuthenticated = useAuthStore(selectIsAuthenticated)
  const role = useAuthStore((state) => state.user?.role)
  const mfaSetupRequired = useAuthStore((state) => state.mfaSetupRequired)

  useEffect(() => {
    if (isInitializing) void bootstrapSession()
  }, [isInitializing])

  if (isInitializing) return <PageSpinner label="Checking your session" fullScreen />

  if (isAuthenticated && role) {
    // Signed in already (or just now, from the form): go where they were heading, if it is theirs.
    return (
      <Navigate
        to={destinationAfterLogin({ role, mfaSetupRequired }, fromPath(location.state))}
        replace
      />
    )
  }

  return <Outlet />
}
