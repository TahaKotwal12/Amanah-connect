import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type {
  ApproveRegistrationRequest,
  CreateInviteRequest,
  CreatedInviteView,
  EmailedInviteView,
  InviteByEmailRequest,
  InviteView,
  PageResponse,
  PublicInviteView,
  PublicRegisterRequest,
  PublicRegisterResponse,
  RegistrationView,
  Uuid,
} from '@/types'
import type { InviteListParams, RegistrationListParams } from '../queryKeys'

export async function fetchInvites(params: InviteListParams): Promise<PageResponse<InviteView>> {
  const { data } = await api.get<PageResponse<InviteView>>('/community/member-invites', {
    params: toQuery(params),
  })
  return data
}

/** The link and QR code are returned once, here, and never again. */
export async function createInvite(body: CreateInviteRequest): Promise<CreatedInviteView> {
  const { data } = await api.post<CreatedInviteView>('/community/member-invites', body)
  return data
}

export async function inviteByEmail(body: InviteByEmailRequest): Promise<EmailedInviteView> {
  const { data } = await api.post<EmailedInviteView>('/community/member-invites/email', body)
  return data
}

export async function revokeInvite(id: Uuid): Promise<InviteView> {
  const { data } = await api.post<InviteView>(`/community/member-invites/${id}/revoke`)
  return data
}

export async function fetchRegistrations(
  params: RegistrationListParams,
): Promise<PageResponse<RegistrationView>> {
  const { data } = await api.get<PageResponse<RegistrationView>>('/community/registrations', {
    params: toQuery(params),
  })
  return data
}

export async function approveRegistration(
  id: Uuid,
  body: ApproveRegistrationRequest = {},
): Promise<RegistrationView> {
  const { data } = await api.post<RegistrationView>(`/community/registrations/${id}/approve`, body)
  return data
}

export async function rejectRegistration(id: Uuid, reason: string): Promise<RegistrationView> {
  const { data } = await api.post<RegistrationView>(`/community/registrations/${id}/reject`, {
    reason,
  })
  return data
}

// ---- the public join page ---------------------------------------------------------------------

export async function fetchPublicInvite(token: string): Promise<PublicInviteView> {
  const { data } = await api.get<PublicInviteView>(`/public/invites/${encodeURIComponent(token)}`)
  return data
}

export async function registerWithInvite(
  token: string,
  body: PublicRegisterRequest,
): Promise<PublicRegisterResponse> {
  const { data } = await api.post<PublicRegisterResponse>(
    `/public/invites/${encodeURIComponent(token)}/register`,
    body,
  )
  return data
}
