import { installMockApi, problem } from '@/test/mockApi'
import { resetSession, setSession } from '@/test/fixtures'
import { authStore } from '@/store/authStore'
import { ApiError } from './apiError'
import { api, setSessionExpiredHandler } from './axios'

const NEW_TOKEN_REPLY = {
  status: 200,
  delayMs: 20, // long enough for parallel requests to overlap the refresh
  data: { accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 900, mfaSetupRequired: false },
}

/** Answers 200 for the fresh token and 401 for anything else, like the real API after expiry. */
function protectedEndpoint(config: { headers: { get(name: string): unknown } }) {
  return config.headers.get('Authorization') === 'Bearer new-token'
    ? { status: 200, data: { ok: true } }
    : problem(401, 'UNAUTHENTICATED')
}

let mock: ReturnType<typeof installMockApi>
const expired = vi.fn()

beforeEach(() => {
  expired.mockReset()
  setSessionExpiredHandler(expired)
  setSession('COMMUNITY_ADMIN')
  authStore.getState().setAccessToken('old-token')
})

afterEach(() => {
  mock.restore()
  resetSession()
})

describe('request defaults', () => {
  it('sends the CSRF header, credentials and the bearer token', async () => {
    mock = installMockApi({ 'GET /members': () => ({ status: 200, data: [] }) })
    await api.get('/members')
    expect(mock.calls[0]).toMatchObject({
      authorization: 'Bearer old-token',
      requestedWith: 'amanah-web',
    })
    expect(api.defaults.baseURL).toBe('/api/v1')
    expect(api.defaults.withCredentials).toBe(true)
  })

  it('sends no Authorization header when signed out', async () => {
    authStore.getState().clearSession()
    mock = installMockApi({ 'GET /public/plans': () => ({ status: 200, data: [] }) })
    await api.get('/public/plans')
    expect(mock.calls[0]?.authorization).toBeUndefined()
  })
})

describe('refresh on 401', () => {
  it('refreshes once and retries once, for any number of parallel requests', async () => {
    mock = installMockApi({
      'GET /a': protectedEndpoint,
      'GET /b': protectedEndpoint,
      'GET /c': protectedEndpoint,
      'POST /auth/refresh': () => NEW_TOKEN_REPLY,
    })

    const results = await Promise.all([api.get('/a'), api.get('/b'), api.get('/c')])

    expect(results.map((r) => r.status)).toEqual([200, 200, 200])
    expect(mock.count('POST /auth/refresh')).toBe(1) // single flight
    for (const path of ['a', 'b', 'c']) {
      expect(mock.count(`GET /${path}`)).toBe(2) // the 401, then exactly one retry
    }
    expect(authStore.getState().accessToken).toBe('new-token')
    expect(expired).not.toHaveBeenCalled()
  })

  it('uses a fresh refresh for a later expiry (the shared promise is released)', async () => {
    mock = installMockApi({
      'GET /a': protectedEndpoint,
      'POST /auth/refresh': () => NEW_TOKEN_REPLY,
    })
    await api.get('/a')
    authStore.getState().setAccessToken('old-token') // expires again
    await api.get('/a')
    expect(mock.count('POST /auth/refresh')).toBe(2)
  })

  it('signs the user out once, however many requests were waiting, when refresh fails', async () => {
    mock = installMockApi({
      'GET /a': () => problem(401, 'UNAUTHENTICATED'),
      'GET /b': () => problem(401, 'UNAUTHENTICATED'),
      'GET /c': () => problem(401, 'UNAUTHENTICATED'),
      'POST /auth/refresh': () => ({
        ...problem(401, 'INVALID_REFRESH_TOKEN'),
        delayMs: 20,
      }),
    })

    const outcomes = await Promise.allSettled([api.get('/a'), api.get('/b'), api.get('/c')])

    for (const outcome of outcomes) {
      expect(outcome.status).toBe('rejected')
      const reason = (outcome as PromiseRejectedResult).reason as unknown
      expect(reason).toBeInstanceOf(ApiError)
      expect((reason as ApiError).status).toBe(401)
    }
    expect(mock.count('POST /auth/refresh')).toBe(1)
    expect(expired).toHaveBeenCalledTimes(1)
    const state = authStore.getState()
    expect(state.accessToken).toBeNull()
    expect(state.user).toBeNull()
    expect(state.sessionExpired).toBe(true)
  })

  it('does not loop when the retried request is still 401', async () => {
    mock = installMockApi({
      'GET /a': () => problem(401, 'UNAUTHENTICATED'),
      'POST /auth/refresh': () => NEW_TOKEN_REPLY,
    })
    await expect(api.get('/a')).rejects.toBeInstanceOf(ApiError)
    expect(mock.count('GET /a')).toBe(2)
    expect(mock.count('POST /auth/refresh')).toBe(1)
    expect(expired).toHaveBeenCalledTimes(1)
  })

  it('keeps the session when the retry fails for a non-auth reason', async () => {
    mock = installMockApi({
      'GET /a': (config) =>
        config.headers.get('Authorization') === 'Bearer new-token'
          ? problem(500, 'INTERNAL_ERROR')
          : problem(401, 'UNAUTHENTICATED'),
      'POST /auth/refresh': () => NEW_TOKEN_REPLY,
    })
    await expect(api.get('/a')).rejects.toMatchObject({ status: 500 })
    expect(expired).not.toHaveBeenCalled()
    expect(authStore.getState().accessToken).toBe('new-token')
  })

  it.each([
    ['POST /auth/login', 'INVALID_CREDENTIALS'],
    ['POST /auth/login/2fa', 'INVALID_MFA_CODE'],
    ['POST /auth/password/reset', 'INVALID_TOKEN'],
    ['POST /auth/accept-invite', 'INVALID_TOKEN'],
  ])(
    'does not refresh after a 401 from %s: that is an answer, not an expiry',
    async (key, code) => {
      mock = installMockApi({
        [key]: () => problem(401, code),
        'POST /auth/refresh': () => NEW_TOKEN_REPLY,
      })
      const url = key.split(' ')[1] ?? ''
      await expect(api.post(url, {})).rejects.toMatchObject({ code, status: 401 })
      expect(mock.count('POST /auth/refresh')).toBe(0)
      expect(expired).not.toHaveBeenCalled()
    },
  )
})

describe('401s that are answers, not expiry', () => {
  it('a wrong 2FA code on a signed-in call is shown to the caller, not treated as an expired session', async () => {
    mock = installMockApi({
      'POST /auth/2fa/enable': () => problem(401, 'INVALID_MFA_CODE'),
      'POST /auth/refresh': () => NEW_TOKEN_REPLY,
    })
    await expect(api.post('/auth/2fa/enable', { code: '000000' })).rejects.toMatchObject({
      status: 401,
      code: 'INVALID_MFA_CODE',
    })
    expect(mock.count('POST /auth/refresh')).toBe(0)
    expect(mock.count('POST /auth/2fa/enable')).toBe(1)
    expect(expired).not.toHaveBeenCalled()
    expect(authStore.getState().accessToken).toBe('old-token') // still signed in
  })
})

describe('errors', () => {
  it('turns problem+json into an ApiError with code, status, detail and field errors', async () => {
    mock = installMockApi({
      'POST /members': () =>
        problem(400, 'VALIDATION_FAILED', 'One or more fields are invalid.', {
          errors: [{ field: 'fullName', message: 'must not be blank' }],
        }),
    })
    const error = (await api.post('/members', {}).catch((e: unknown) => e)) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
      detail: 'One or more fields are invalid.',
    })
    expect(error.fieldErrors).toEqual([{ field: 'fullName', message: 'must not be blank' }])
  })

  it('exposes Retry-After on a 429', async () => {
    mock = installMockApi({
      'POST /auth/login': () => problem(429, 'RATE_LIMITED', 'slow', {}, { 'retry-after': '30' }),
    })
    await expect(api.post('/auth/login', {})).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 30,
    })
  })

  it('flags a token that only allows 2FA setup so the route guard can react', async () => {
    mock = installMockApi({ 'GET /members': () => problem(403, 'MFA_SETUP_REQUIRED') })
    expect(authStore.getState().mfaSetupRequired).toBe(false)
    await expect(api.get('/members')).rejects.toMatchObject({ code: 'MFA_SETUP_REQUIRED' })
    expect(authStore.getState().mfaSetupRequired).toBe(true)
  })
})
