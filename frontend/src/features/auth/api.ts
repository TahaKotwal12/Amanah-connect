import { api } from '@/lib/axios'
import type {
  AcceptInviteResponse,
  LoginRequest,
  LoginResponse,
  MfaLoginRequest,
  RecoveryCodesResponse,
  TwoFactorSetupResponse,
} from '@/types'

/** POST /auth/login. A token, or an MFA challenge when the account has 2FA. Sets the refresh cookie. */
export async function login(body: LoginRequest): Promise<LoginResponse> {
  const { data } = await api.post<LoginResponse>('/auth/login', body)
  return data
}

/** POST /auth/login/2fa: the challenge token plus a 6-digit code or a recovery code. */
export async function loginWithSecondFactor(body: MfaLoginRequest): Promise<LoginResponse> {
  const { data } = await api.post<LoginResponse>('/auth/login/2fa', body)
  return data
}

/** Always 202, whether or not the address has an account: the caller must not treat it as proof. */
export async function requestPasswordReset(email: string): Promise<void> {
  await api.post('/auth/password/forgot', { email })
}

export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await api.post('/auth/password/reset', { token, newPassword })
}

export async function acceptInvite(
  token: string,
  newPassword: string,
): Promise<AcceptInviteResponse> {
  const { data } = await api.post<AcceptInviteResponse>('/auth/accept-invite', {
    token,
    newPassword,
  })
  return data
}

export async function startTwoFactorSetup(): Promise<TwoFactorSetupResponse> {
  const { data } = await api.post<TwoFactorSetupResponse>('/auth/2fa/setup')
  return data
}

/** Turns 2FA on and returns the recovery codes. They are shown once: the server never repeats them. */
export async function enableTwoFactor(code: string): Promise<RecoveryCodesResponse> {
  const { data } = await api.post<RecoveryCodesResponse>('/auth/2fa/enable', { code })
  return data
}
