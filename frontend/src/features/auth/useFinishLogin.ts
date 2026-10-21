import { useLocation, useNavigate } from 'react-router'
import { loadCurrentUser } from '@/lib/session'
import { paths } from '@/routes/paths'
import { authStore } from '@/store/authStore'
import type { LoginResponse } from '@/types'
import { applyLoginResponse, destinationAfterLogin, fromPath } from './loginFlow'

/**
 * What to do with a successful /auth/login or /auth/login/2fa response: go to the second step, or
 * load the user and go to the right dashboard (or to forced 2FA setup). Throws if the user cannot be
 * loaded, after dropping the half-made session so the caller can show an error.
 */
export function useFinishLogin() {
  const navigate = useNavigate()
  const location = useLocation()
  const from = fromPath(location.state)

  return async function finishLogin(response: LoginResponse): Promise<void> {
    const outcome = applyLoginResponse(response)
    if (outcome.step === 'mfa') {
      // Carry "where they were going" through the second step.
      const state: unknown = location.state
      await navigate(paths.twoFactorChallenge, { state })
      return
    }
    try {
      const me = await loadCurrentUser()
      await navigate(
        destinationAfterLogin(
          { role: me.user.role, mfaSetupRequired: me.flags.mfaSetupRequired },
          from,
        ),
        { replace: true },
      )
    } catch (error) {
      authStore.getState().clearSession()
      throw error
    }
  }
}
