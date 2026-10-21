import { authStore } from '@/store/authStore'
import { homePathFor, paths } from '@/routes/paths'
import { isMfaChallenge, type LoginResponse, type UserRole } from '@/types'

/**
 * The sign-in state machine, in one place.
 *
 *   anonymous --password ok, no 2FA--------------------> session (token held in memory)
 *   anonymous --password ok, 2FA on--> challenge --code ok--> session
 *   challenge --token expires or user backs out--------> anonymous
 *   session   --token says mfa_setup_required----------> forced to /2fa/setup until 2FA is on
 *
 * The store holds the state; these functions are the only transitions out of a login response.
 */
export type LoginStep =
  /** A second factor is needed: the store now holds the challenge. */
  | { step: 'mfa' }
  /** Signed in: the store holds the access token. Load the user next. */
  | { step: 'session'; mfaSetupRequired: boolean }

export function applyLoginResponse(response: LoginResponse): LoginStep {
  const store = authStore.getState()
  if (isMfaChallenge(response)) {
    store.startMfaChallenge(response.mfaToken, response.expiresIn)
    return { step: 'mfa' }
  }
  store.setAccessToken(response.accessToken, response.mfaSetupRequired)
  return { step: 'session', mfaSetupRequired: response.mfaSetupRequired }
}

/** Where to go once signed in. The page the visitor was heading to wins only inside their own area. */
export function destinationAfterLogin(
  user: { role: UserRole; mfaSetupRequired: boolean },
  from?: string | null,
): string {
  if (user.mfaSetupRequired) return paths.twoFactorSetup
  const area = user.role === 'SUPER_ADMIN' ? paths.superadmin.root : paths.admin.root
  if (from && (from === area || from.startsWith(`${area}/`))) return from
  return homePathFor(user.role)
}

/** The router remembers where a guarded page sent the visitor from: as a Location or a plain path. */
export function fromPath(state: unknown): string | null {
  if (typeof state !== 'object' || state === null || !('from' in state)) return null
  const from = state.from
  if (typeof from === 'string') return from
  if (typeof from === 'object' && from !== null && 'pathname' in from) {
    const { pathname, search, hash } = from as { pathname: string; search?: string; hash?: string }
    return `${pathname}${search ?? ''}${hash ?? ''}`
  }
  return null
}

/** Why the visitor is on the sign-in page, for the message at the top. */
export type LoginReason = 'session-expired' | 'mfa-expired' | 'password-reset' | 'invite-accepted'

export function reasonOf(state: unknown): LoginReason | null {
  if (typeof state !== 'object' || state === null || !('reason' in state)) return null
  const reason = state.reason
  return reason === 'mfa-expired' || reason === 'password-reset' || reason === 'invite-accepted'
    ? reason
    : null
}
