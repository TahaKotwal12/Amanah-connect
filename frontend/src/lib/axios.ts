import axios, { type AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { authStore } from '@/store/authStore'
import type { TokenResponse } from '@/types'
import { type ApiError, toApiError } from './apiError'

export const API_BASE_URL = '/api/v1'

/** The header the backend requires on cookie-authenticated endpoints (refresh, logout). */
const CSRF_HEADER = { 'X-Requested-With': 'amanah-web' } as const

const baseConfig = {
  baseURL: API_BASE_URL,
  withCredentials: true,
  timeout: 30_000,
  headers: { ...CSRF_HEADER, Accept: 'application/json, application/problem+json' },
}

/** The app's client: bearer token, refresh-on-401, errors normalised to ApiError. */
export const api: AxiosInstance = axios.create(baseConfig)

/**
 * A bare client with no interceptors, for the refresh call itself and for cookie-only calls
 * (logout). Using `api` for those would recurse into the 401 handler.
 */
export const rawApi: AxiosInstance = axios.create(baseConfig)

// ---- session expiry hook ----------------------------------------------------------------------

let onSessionExpired: () => void = () => {
  window.location.assign('/login')
}

/** Called once refresh has failed and the session is cleared. The router registers a SPA redirect. */
export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

// ---- single-flight refresh --------------------------------------------------------------------

let refreshInFlight: Promise<string> | null = null

async function requestNewAccessToken(): Promise<string> {
  const { data } = await rawApi.post<TokenResponse>('/auth/refresh')
  authStore.getState().setAccessToken(data.accessToken, data.mfaSetupRequired)
  return data.accessToken
}

/**
 * Serialise across tabs where the browser can. The refresh token rotates on every use and the
 * server treats a replayed one as theft and revokes the session, so two tabs refreshing with the
 * same cookie at once would sign the user out. With the lock the second tab waits, then sends the
 * cookie the first tab already rotated.
 */
async function withCrossTabLock<T>(job: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && 'locks' in navigator) {
    return await navigator.locks.request('amanah-refresh', job)
  }
  return job()
}

/**
 * Get a fresh access token from the refresh cookie. Every caller that arrives while a refresh is
 * running shares it, so N parallel 401s cause exactly one /auth/refresh. Rejects with ApiError.
 */
export function refreshAccessToken(): Promise<string> {
  refreshInFlight ??= withCrossTabLock(requestNewAccessToken)
    .catch((error: unknown) => {
      throw toApiError(error)
    })
    .finally(() => {
      refreshInFlight = null
    })
  return refreshInFlight
}

// ---- interceptors -----------------------------------------------------------------------------

interface RetryableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean
}

/** 401s from these are answers, not an expired session: never refresh-and-retry them. */
const NO_REFRESH_PATHS = [
  '/auth/login',
  '/auth/login/2fa',
  '/auth/refresh',
  '/auth/logout',
  '/auth/password/forgot',
  '/auth/password/reset',
  '/auth/accept-invite',
]

function shouldRefresh(
  config: RetryableConfig | undefined,
  error: ApiError,
): config is RetryableConfig {
  if (!config || config._retried || error.status !== 401) return false
  // Only "your access token is not good" means refresh. Other 401s are answers about the request
  // itself (a wrong 2FA code, wrong credentials) and must reach the caller untouched: refreshing and
  // retrying them would sign the person out for a typo.
  if (error.code !== 'UNAUTHENTICATED') return false
  const path = (config.url ?? '').split('?')[0] ?? ''
  if (NO_REFRESH_PATHS.includes(path)) return false
  // Public endpoints (/public/**) take no token, so a 401 there cannot be fixed by refreshing.
  if (path.startsWith('/public/')) return false
  return true
}

api.interceptors.request.use((config) => {
  const token = authStore.getState().accessToken
  if (token && !config.headers.has('Authorization')) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  async (rawError: AxiosError | ApiError) => {
    const error = toApiError(rawError)
    const config = (rawError as AxiosError).config as RetryableConfig | undefined

    // The token only allows 2FA setup: tell the router so ProtectedRoute can redirect.
    if (error.status === 403 && error.code === 'MFA_SETUP_REQUIRED') {
      authStore.getState().setMfaSetupRequired(true)
    }

    if (!shouldRefresh(config, error)) throw error

    try {
      const token = await refreshAccessToken()
      config._retried = true
      config.headers.set('Authorization', `Bearer ${token}`)
      return await api.request(config)
    } catch (retryError) {
      const failure = toApiError(retryError)
      // Refresh itself was refused (cookie missing, expired, revoked): the session is over.
      // A retry that failed for another reason (say a 500) leaves the session alone.
      if (failure.code === 'INVALID_REFRESH_TOKEN' || failure.status === 401) {
        // Parallel requests all land here after the one shared refresh fails: end the session once.
        if (!authStore.getState().sessionExpired) {
          authStore.getState().clearSession({ expired: true })
          onSessionExpired()
        }
        throw error
      }
      throw failure
    }
  },
)
