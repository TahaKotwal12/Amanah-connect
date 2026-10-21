import type { MeResponse, UserRole } from '@/types'
import { authStore } from '@/store/authStore'

export function meFor(role: UserRole, flags: Partial<MeResponse['flags']> = {}): MeResponse {
  const superAdmin = role === 'SUPER_ADMIN'
  return {
    user: {
      id: '3f2b8c1e-6d7a-4c58-9a55-2d1a0e8c9b11',
      email: superAdmin ? 'root@example.test' : 'admin@example.test',
      fullName: superAdmin ? 'Platform Admin' : 'Community Admin',
      role,
      status: 'ACTIVE',
    },
    community: superAdmin
      ? null
      : {
          id: '7a0e2f54-1b3c-4d8e-8f6a-5c9d2e1f0a47',
          name: 'Test Community',
          slug: 'test-community',
          status: 'ACTIVE',
        },
    flags: { totpEnabled: true, mfaSetupRequired: false, mfaMandatory: superAdmin, ...flags },
  }
}

/** Put the store in a known state: signed in as `role`, or anonymous when role is null. */
export function setSession(role: UserRole | null, flags: Partial<MeResponse['flags']> = {}): void {
  const state = authStore.getState()
  state.clearSession()
  state.finishInitializing()
  if (role) {
    state.setAccessToken('test-access-token', flags.mfaSetupRequired ?? false)
    state.setMe(meFor(role, flags))
  }
}

/** Back to a fresh page load: anonymous and still bootstrapping. */
export function resetSession(): void {
  authStore.setState({ isInitializing: true })
  authStore.getState().clearSession()
}
