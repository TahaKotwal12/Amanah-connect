import { authStore } from '@/store/authStore'
import { meFor, resetSession } from '@/test/fixtures'
import { installMockApi, problem } from '@/test/mockApi'
import { setSessionExpiredHandler } from './axios'
import { bootstrapSession, logout, resetBootstrapForTests } from './session'

let mock: ReturnType<typeof installMockApi>
const expired = vi.fn()

beforeEach(() => {
  resetSession()
  resetBootstrapForTests()
  expired.mockReset()
  setSessionExpiredHandler(expired)
})

afterEach(() => {
  mock.restore()
})

describe('bootstrapSession', () => {
  it('restores the session: refresh, then /auth/me', async () => {
    mock = installMockApi({
      'POST /auth/refresh': () => ({
        status: 200,
        data: {
          accessToken: 'fresh',
          tokenType: 'Bearer',
          expiresIn: 900,
          mfaSetupRequired: false,
        },
      }),
      'GET /auth/me': () => ({ status: 200, data: meFor('COMMUNITY_ADMIN') }),
    })

    expect(authStore.getState().isInitializing).toBe(true)
    await bootstrapSession()

    const state = authStore.getState()
    expect(state.isInitializing).toBe(false)
    expect(state.accessToken).toBe('fresh')
    expect(state.user?.role).toBe('COMMUNITY_ADMIN')
    expect(state.community?.name).toBe('Test Community')
    expect(mock.calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'POST /auth/refresh',
      'GET /auth/me',
    ])
    // The cookie call carries the CSRF header; /auth/me carries the token it just received.
    expect(mock.calls[0]?.requestedWith).toBe('amanah-web')
    expect(mock.calls[1]?.authorization).toBe('Bearer fresh')
  })

  it('leaves a visitor with no cookie anonymous, without redirecting', async () => {
    mock = installMockApi({
      'POST /auth/refresh': () => problem(401, 'INVALID_REFRESH_TOKEN'),
    })
    await bootstrapSession()
    const state = authStore.getState()
    expect(state.isInitializing).toBe(false)
    expect(state.user).toBeNull()
    expect(state.accessToken).toBeNull()
    expect(state.sessionExpired).toBe(false)
    expect(expired).not.toHaveBeenCalled()
  })

  it('treats an unreachable API as signed out and still finishes initializing', async () => {
    mock = installMockApi({
      'POST /auth/refresh': () => problem(503, 'INTERNAL_ERROR'),
    })
    await bootstrapSession()
    expect(authStore.getState().isInitializing).toBe(false)
    expect(authStore.getState().user).toBeNull()
  })

  it('flags a token that needs 2FA setup', async () => {
    mock = installMockApi({
      'POST /auth/refresh': () => ({
        status: 200,
        data: { accessToken: 't', tokenType: 'Bearer', expiresIn: 900, mfaSetupRequired: true },
      }),
      'GET /auth/me': () => ({
        status: 200,
        data: meFor('SUPER_ADMIN', { mfaSetupRequired: true, totpEnabled: false }),
      }),
    })
    await bootstrapSession()
    expect(authStore.getState().mfaSetupRequired).toBe(true)
  })

  it('runs once even when called twice (React StrictMode)', async () => {
    mock = installMockApi({
      'POST /auth/refresh': () => problem(401, 'INVALID_REFRESH_TOKEN'),
    })
    await Promise.all([bootstrapSession(), bootstrapSession()])
    expect(mock.count('POST /auth/refresh')).toBe(1)
  })
})

describe('logout', () => {
  it('revokes the cookie server-side and clears memory', async () => {
    mock = installMockApi({ 'POST /auth/logout': () => ({ status: 204 }) })
    authStore.getState().setAccessToken('t')
    authStore.getState().setMe(meFor('COMMUNITY_ADMIN'))

    await logout()

    expect(mock.count('POST /auth/logout')).toBe(1)
    expect(mock.calls[0]?.requestedWith).toBe('amanah-web')
    expect(authStore.getState().user).toBeNull()
    expect(authStore.getState().accessToken).toBeNull()
  })

  it('signs out locally even if the server call fails', async () => {
    mock = installMockApi({ 'POST /auth/logout': () => problem(500, 'INTERNAL_ERROR') })
    authStore.getState().setAccessToken('t')
    authStore.getState().setMe(meFor('SUPER_ADMIN'))
    await logout()
    expect(authStore.getState().user).toBeNull()
  })
})
