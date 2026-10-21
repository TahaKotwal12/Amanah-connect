import { api } from '@/lib/axios'
import type { InviteRow, PageResponse, Uuid } from '@/types'
import type { InviteListParams } from '../queryKeys'
import { toQuery } from './params'

export async function fetchInvites(params: InviteListParams): Promise<PageResponse<InviteRow>> {
  const { data } = await api.get<PageResponse<InviteRow>>('/admin/invites', {
    params: toQuery(params),
  })
  return data
}

export async function resendInvite(userId: Uuid): Promise<void> {
  await api.post(`/admin/invites/${userId}/resend`)
}

export async function revokeInvite(userId: Uuid): Promise<void> {
  await api.post(`/admin/invites/${userId}/revoke`)
}
