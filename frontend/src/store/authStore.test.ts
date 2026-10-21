import { meFor, resetSession } from '@/test/fixtures'
import { authStore, selectIsAuthenticated, selectRole } from './authStore'

beforeEach(resetSession)

describe('authStore', () => {
  it('starts anonymous and initializing', () => {
    const state = authStore.getState()
    expect(state.user).toBeNull()
    expect(state.accessToken).toBeNull()
    expect(state.isInitializing).toBe(true)
    expect(selectIsAuthenticated(state)).toBe(false)
  })

  it('becomes authenticated once it has both a token and a user', () => {
    const state = authStore.getState()
    state.setAccessToken('abc', false)
    expect(selectIsAuthenticated(authStore.getState())).toBe(false) // token alone is not enough
    state.setMe(meFor('SUPER_ADMIN'))
    expect(selectIsAuthenticated(authStore.getState())).toBe(true)
    expect(selectRole(authStore.getState())).toBe('SUPER_ADMIN')
    expect(authStore.getState().community).toBeNull()
  })

  it('takes the MFA flags from the token response and from /auth/me', () => {
    authStore.getState().setAccessToken('abc', true)
    expect(authStore.getState().mfaSetupRequired).toBe(true)
    authStore
      .getState()
      .setMe(meFor('COMMUNITY_ADMIN', { mfaSetupRequired: false, totpEnabled: true }))
    expect(authStore.getState().mfaSetupRequired).toBe(false)
    expect(authStore.getState().totpEnabled).toBe(true)
  })

  it('runs the two-step login: challenge, then session', () => {
    authStore.getState().startMfaChallenge('mfa-token', 300)
    const { mfa } = authStore.getState()
    expect(mfa.status).toBe('challenge')
    if (mfa.status === 'challenge') {
      expect(mfa.challenge.mfaToken).toBe('mfa-token')
      expect(mfa.challenge.expiresAt).toBeGreaterThan(Date.now() + 290_000)
    }
    authStore.getState().setAccessToken('final')
    expect(authStore.getState().mfa.status).toBe('none')
  })

  it('clears everything on sign-out and can mark the end as an expiry', () => {
    authStore.getState().setAccessToken('abc', true)
    authStore.getState().setMe(meFor('COMMUNITY_ADMIN'))
    authStore.getState().clearSession({ expired: true })
    const state = authStore.getState()
    expect(state).toMatchObject({
      user: null,
      community: null,
      accessToken: null,
      mfaSetupRequired: false,
      sessionExpired: true,
    })
    state.acknowledgeSessionExpired()
    expect(authStore.getState().sessionExpired).toBe(false)
  })

  it('never touches browser storage', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const state = authStore.getState()
    state.setAccessToken('secret-token', true)
    state.setMe(meFor('SUPER_ADMIN'))
    state.startMfaChallenge('secret-mfa', 300)
    state.clearSession()
    expect(setItem).not.toHaveBeenCalled()
    expect(localStorage).toHaveLength(0)
    expect(sessionStorage).toHaveLength(0)
    setItem.mockRestore()
  })
})
