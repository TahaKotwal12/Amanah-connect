import type { CommunityStatus, UserRole, UserStatus, Uuid } from './common'

export interface LoginRequest {
  email: string
  password: string
}

export interface MfaLoginRequest {
  mfaToken: string
  /** 6-digit TOTP code. Send either `code` or `recoveryCode`. */
  code?: string
  recoveryCode?: string
}

export interface ForgotPasswordRequest {
  email: string
}
export interface ResetPasswordRequest {
  token: string
  newPassword: string
}
export interface AcceptInviteRequest {
  token: string
  newPassword: string
}
export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}
export interface TwoFactorEnableRequest {
  code: string
}
export interface TwoFactorDisableRequest {
  password: string
  code?: string
  recoveryCode?: string
}

/** The refresh token is never in a body: it lives in the HttpOnly `amanah_refresh` cookie. */
export interface TokenResponse {
  accessToken: string
  tokenType: 'Bearer'
  /** Seconds. */
  expiresIn: number
  mfaSetupRequired: boolean
}

export interface MfaChallengeResponse {
  mfaRequired: true
  mfaToken: string
  /** Seconds (about 5 minutes). */
  expiresIn: number
}

/** POST /auth/login and /auth/login/2fa return one of these. */
export type LoginResponse = TokenResponse | MfaChallengeResponse

export function isMfaChallenge(response: LoginResponse): response is MfaChallengeResponse {
  return 'mfaRequired' in response && response.mfaRequired
}

export interface AcceptInviteResponse {
  mfaSetupRequired: boolean
}
export interface TwoFactorSetupResponse {
  secret: string
  otpauthUri: string
}
export interface RecoveryCodesResponse {
  recoveryCodes: string[]
}

export interface UserSummary {
  id: Uuid
  email: string
  fullName: string
  role: UserRole
  status: UserStatus
}
export interface CommunitySummary {
  id: Uuid
  name: string
  slug: string
  status: CommunityStatus
}
export interface Flags {
  totpEnabled: boolean
  mfaSetupRequired: boolean
  mfaMandatory: boolean
}
/** GET /auth/me. `community` is null for SUPER_ADMIN. */
export interface MeResponse {
  user: UserSummary
  community: CommunitySummary | null
  flags: Flags
}
