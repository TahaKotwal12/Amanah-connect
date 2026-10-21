import { create } from 'zustand'
import type { CommunitySummary, MeResponse, UserRole, UserSummary } from '@/types'

/**
 * Session state, in memory only. Nothing here is ever written to localStorage, sessionStorage or
 * IndexedDB: the access token lives in this closure and the refresh token is an HttpOnly cookie
 * the page cannot read. A reload starts anonymous and bootstraps through /auth/refresh.
 */

/** A password login that needs a second factor before it becomes a session. */
export interface MfaChallenge {
  mfaToken: string
  /** Epoch ms after which the token is dead and the user must start the login again. */
  expiresAt: number
}

export type MfaState =
  | { status: 'none' }
  /** Password accepted, waiting for the TOTP or recovery code. */
  | { status: 'challenge'; challenge: MfaChallenge }

export interface AuthState {
  user: UserSummary | null
  community: CommunitySummary | null
  accessToken: string | null
  /** True until the first /auth/refresh + /auth/me attempt finishes. Guards must wait for it. */
  isInitializing: boolean
  /** The access token carries mfa_setup_required: only 2FA setup endpoints will work. */
  mfaSetupRequired: boolean
  totpEnabled: boolean
  mfaMandatory: boolean
  mfa: MfaState
  /** Set when a session died on its own (refresh failed), so the login page can explain. */
  sessionExpired: boolean

  setAccessToken: (token: string, mfaSetupRequired?: boolean) => void
  setMe: (me: MeResponse) => void
  startMfaChallenge: (mfaToken: string, expiresInSeconds: number) => void
  clearMfaChallenge: () => void
  setMfaSetupRequired: (required: boolean) => void
  finishInitializing: () => void
  /** Forget the session. `expired: true` marks that the user did not ask for it. */
  clearSession: (options?: { expired?: boolean }) => void
  acknowledgeSessionExpired: () => void
}

const anonymous = {
  user: null,
  community: null,
  accessToken: null,
  mfaSetupRequired: false,
  totpEnabled: false,
  mfaMandatory: false,
  mfa: { status: 'none' } as MfaState,
}

export const useAuthStore = create<AuthState>()((set) => ({
  ...anonymous,
  isInitializing: true,
  sessionExpired: false,

  setAccessToken: (token, mfaSetupRequired) =>
    set((state) => ({
      accessToken: token,
      mfaSetupRequired: mfaSetupRequired ?? state.mfaSetupRequired,
      mfa: { status: 'none' },
      sessionExpired: false,
    })),

  setMe: (me) =>
    set({
      user: me.user,
      community: me.community,
      mfaSetupRequired: me.flags.mfaSetupRequired,
      totpEnabled: me.flags.totpEnabled,
      mfaMandatory: me.flags.mfaMandatory,
    }),

  startMfaChallenge: (mfaToken, expiresInSeconds) =>
    set({
      mfa: {
        status: 'challenge',
        challenge: { mfaToken, expiresAt: Date.now() + expiresInSeconds * 1000 },
      },
    }),

  clearMfaChallenge: () => set({ mfa: { status: 'none' } }),

  setMfaSetupRequired: (required) => set({ mfaSetupRequired: required }),

  finishInitializing: () => set({ isInitializing: false }),

  clearSession: (options) => set({ ...anonymous, sessionExpired: options?.expired ?? false }),

  acknowledgeSessionExpired: () => set({ sessionExpired: false }),
}))

// Non-hook access for code outside React (the axios interceptors).
export const authStore = useAuthStore

export const selectIsAuthenticated = (state: AuthState): boolean =>
  state.user !== null && state.accessToken !== null

export const selectRole = (state: AuthState): UserRole | null => state.user?.role ?? null
