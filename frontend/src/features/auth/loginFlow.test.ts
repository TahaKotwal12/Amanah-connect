import { authStore } from '@/store/authStore'
import { resetSession } from '@/test/fixtures'
import { applyLoginResponse, destinationAfterLogin, fromPath, reasonOf } from './loginFlow'

beforeEach(resetSession)

describe('applyLoginResponse: the transitions out of a login response', () => {
  it('a token response signs in: the token is held in memory and there is no challenge', () => {
    const step = applyLoginResponse({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresIn: 900,
      mfaSetupRequired: false,
    })
    expect(step).toEqual({ step: 'session', mfaSetupRequired: false })
    expect(authStore.getState().accessToken).toBe('tok')
    expect(authStore.getState().mfa.status).toBe('none')
  })

  it('a challenge response holds the challenge and no token', () => {
    const step = applyLoginResponse({ mfaRequired: true, mfaToken: 'mfa-1', expiresIn: 300 })
    expect(step).toEqual({ step: 'mfa' })
    const { mfa, accessToken } = authStore.getState()
    expect(accessToken).toBeNull()
    expect(mfa.status).toBe('challenge')
    if (mfa.status === 'challenge') {
      expect(mfa.challenge.mfaToken).toBe('mfa-1')
      expect(mfa.challenge.expiresAt).toBeGreaterThan(Date.now() + 290_000)
    }
  })

  it('completing the second step replaces the challenge with a session', () => {
    applyLoginResponse({ mfaRequired: true, mfaToken: 'mfa-1', expiresIn: 300 })
    applyLoginResponse({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresIn: 900,
      mfaSetupRequired: false,
    })
    expect(authStore.getState().mfa.status).toBe('none')
    expect(authStore.getState().accessToken).toBe('tok')
  })

  it('a token that only allows 2FA setup is flagged, so the guards force the setup page', () => {
    const step = applyLoginResponse({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresIn: 900,
      mfaSetupRequired: true,
    })
    expect(step).toEqual({ step: 'session', mfaSetupRequired: true })
    expect(authStore.getState().mfaSetupRequired).toBe(true)
  })

  it('signing in clears the "your session ended" notice', () => {
    authStore.getState().clearSession({ expired: true })
    applyLoginResponse({
      accessToken: 'tok',
      tokenType: 'Bearer',
      expiresIn: 900,
      mfaSetupRequired: false,
    })
    expect(authStore.getState().sessionExpired).toBe(false)
  })
})

describe('destinationAfterLogin', () => {
  const admin = { role: 'COMMUNITY_ADMIN', mfaSetupRequired: false } as const
  const root = { role: 'SUPER_ADMIN', mfaSetupRequired: false } as const

  it('sends each role to its own dashboard', () => {
    expect(destinationAfterLogin(root)).toBe('/superadmin/dashboard')
    expect(destinationAfterLogin(admin)).toBe('/admin/dashboard')
  })

  it('forces the 2FA setup first, whatever else was asked for', () => {
    expect(destinationAfterLogin({ ...root, mfaSetupRequired: true }, '/superadmin/plans')).toBe(
      '/2fa/setup',
    )
    expect(destinationAfterLogin({ ...admin, mfaSetupRequired: true })).toBe('/2fa/setup')
  })

  it('returns to the page they were heading to, if it is in their own area', () => {
    expect(destinationAfterLogin(admin, '/admin/members?page=2')).toBe('/admin/members?page=2')
    expect(destinationAfterLogin(root, '/superadmin/plans')).toBe('/superadmin/plans')
  })

  it('ignores a destination in someone else’s area, or outside the app', () => {
    expect(destinationAfterLogin(admin, '/superadmin/plans')).toBe('/admin/dashboard')
    expect(destinationAfterLogin(root, '/admin/members')).toBe('/superadmin/dashboard')
    expect(destinationAfterLogin(admin, '/administrator')).toBe('/admin/dashboard') // not the /admin area
    expect(destinationAfterLogin(admin, '//evil.example/admin')).toBe('/admin/dashboard')
    expect(destinationAfterLogin(admin, 'https://evil.example')).toBe('/admin/dashboard')
  })
})

describe('reading router state', () => {
  it('fromPath accepts a Location or a string, and nothing else', () => {
    expect(fromPath({ from: { pathname: '/admin/members', search: '?a=1', hash: '#x' } })).toBe(
      '/admin/members?a=1#x',
    )
    expect(fromPath({ from: { pathname: '/admin' } })).toBe('/admin')
    expect(fromPath({ from: '/admin/billing' })).toBe('/admin/billing')
    expect(fromPath(null)).toBeNull()
    expect(fromPath('x')).toBeNull()
    expect(fromPath({ from: 42 })).toBeNull()
  })

  it('reasonOf accepts only the reasons the login page knows', () => {
    expect(reasonOf({ reason: 'mfa-expired' })).toBe('mfa-expired')
    expect(reasonOf({ reason: 'password-reset' })).toBe('password-reset')
    expect(reasonOf({ reason: 'invite-accepted' })).toBe('invite-accepted')
    expect(reasonOf({ reason: '<script>' })).toBeNull()
    expect(reasonOf(undefined)).toBeNull()
  })
})
