import { authStore } from '@/store/authStore'
import type { MeResponse } from '@/types'
import { api, rawApi, refreshAccessToken } from './axios'
import { queryClient } from './queryClient'

export async function fetchMe(): Promise<MeResponse> {
  const { data } = await api.get<MeResponse>('/auth/me')
  return data
}

/**
 * Load the current user into the store. Call it after a successful login or token change so
 * role, community and the MFA flags are populated.
 */
export async function loadCurrentUser(): Promise<MeResponse> {
  const me = await fetchMe()
  authStore.getState().setMe(me)
  return me
}

let bootstrapPromise: Promise<void> | null = null

/**
 * Run once at app start. The access token is memory-only, so after every page load the only way
 * back in is the HttpOnly refresh cookie: /auth/refresh, then /auth/me. No cookie or a dead one
 * simply leaves the visitor anonymous. Safe to call twice (StrictMode): the work runs once.
 */
export function bootstrapSession(): Promise<void> {
  bootstrapPromise ??= (async () => {
    try {
      await refreshAccessToken()
      await loadCurrentUser()
    } catch {
      // Anonymous visitor, expired cookie, or API down. Either way there is no session.
      authStore.getState().clearSession()
    } finally {
      authStore.getState().finishInitializing()
    }
  })()
  return bootstrapPromise
}

/** Test seam: forget that bootstrap ran. */
export function resetBootstrapForTests(): void {
  bootstrapPromise = null
}

/**
 * End the session on purpose: revoke the refresh token server-side (best effort, the endpoint
 * always answers 204), then drop every trace of the user from memory including cached queries.
 */
export async function logout(): Promise<void> {
  try {
    await rawApi.post('/auth/logout')
  } catch {
    // The cookie may already be gone or the network down: the local session ends regardless.
  }
  authStore.getState().clearSession()
  queryClient.clear()
}
