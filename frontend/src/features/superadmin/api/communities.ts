import { api } from '@/lib/axios'
import type {
  CommunityDraft,
  CommunityListItem,
  CommunityOverview,
  CommunityView,
  CreateCommunityRequest,
  ImportReport,
  PageResponse,
  SubscriptionLine,
  UpdateCommunityRequest,
  Uuid,
} from '@/types'
import type { CommunityListParams } from '../queryKeys'
import { toQuery } from './params'

export async function fetchCommunities(
  params: CommunityListParams,
): Promise<PageResponse<CommunityListItem>> {
  const { data } = await api.get<PageResponse<CommunityListItem>>('/admin/communities', {
    params: toQuery(params),
  })
  return data
}

export async function fetchCommunity(id: Uuid): Promise<CommunityView> {
  const { data } = await api.get<CommunityView>(`/admin/communities/${id}`)
  return data
}

export async function createCommunity(body: CreateCommunityRequest): Promise<CommunityView> {
  const { data } = await api.post<CommunityView>('/admin/communities', body)
  return data
}

export async function updateCommunity(
  id: Uuid,
  body: UpdateCommunityRequest,
): Promise<CommunityView> {
  const { data } = await api.patch<CommunityView>(`/admin/communities/${id}`, body)
  return data
}

export type CommunityAction = 'suspend' | 'activate' | 'archive'

/** Suspend and archive need a reason; activate takes an optional one. */
export async function changeCommunityStatus(
  id: Uuid,
  action: CommunityAction,
  reason?: string,
): Promise<CommunityView> {
  const { data } = await api.post<CommunityView>(`/admin/communities/${id}/${action}`, {
    ...(reason ? { reason } : {}),
  })
  return data
}

export interface ResetOutcome {
  userId: Uuid
  /** INVITATION_RESENT for an admin who never accepted, RESET_EMAIL_SENT otherwise. */
  action: string
}

export async function resetAdminPassword(id: Uuid, userId?: Uuid): Promise<ResetOutcome> {
  const { data } = await api.post<ResetOutcome>(
    `/admin/communities/${id}/reset-admin-password`,
    userId ? { userId } : {},
  )
  return data
}

/** Every call is recorded in the audit trail as SUPPORT_VIEW: only call it from the support view. */
export async function fetchCommunityOverview(id: Uuid): Promise<CommunityOverview> {
  const { data } = await api.get<CommunityOverview>(`/admin/communities/${id}/overview`)
  return data
}

export async function fetchSubscriptionHistory(id: Uuid): Promise<SubscriptionLine[]> {
  const { data } = await api.get<SubscriptionLine[]>(`/admin/communities/${id}/subscriptions`)
  return data
}

export async function exportCommunity(
  id: Uuid,
  format: 'json' | 'csv',
): Promise<{ blob: Blob; filename: string }> {
  const { data } = await api.get<Blob>(`/admin/communities/${id}/export`, {
    params: { format },
    responseType: 'blob',
  })
  return { blob: data, filename: `community-${id}.${format}` }
}

/** The create-community request pre-filled from a lead (the wizard then completes the rest). */
export async function fetchLeadDraft(leadId: Uuid): Promise<CommunityDraft> {
  const { data } = await api.get<CommunityDraft>(`/admin/leads/${leadId}/community-draft`)
  return data
}

// ---- migration import -------------------------------------------------------------------------

export interface ImportFiles {
  members?: File | undefined
  openingBalances?: File | undefined
  openInvoices?: File | undefined
}

/**
 * Dry run: validates every row and changes nothing. The batchId is chosen here so a retry of the
 * same upload is recognised by the server instead of creating a second batch.
 */
export async function uploadImport(
  communityId: Uuid,
  batchId: Uuid,
  files: ImportFiles,
): Promise<ImportReport> {
  const form = new FormData()
  if (files.members) form.append('members', files.members)
  if (files.openingBalances) form.append('openingBalances', files.openingBalances)
  if (files.openInvoices) form.append('openInvoices', files.openInvoices)
  const { data } = await api.post<ImportReport>(`/admin/communities/${communityId}/import`, form, {
    params: { batchId },
    // Let the browser set the multipart boundary.
    headers: { 'Content-Type': undefined },
  })
  return data
}

export async function confirmImport(
  communityId: Uuid,
  batchId: Uuid,
  skipInvalidRows: boolean,
): Promise<ImportReport> {
  const { data } = await api.post<ImportReport>(
    `/admin/communities/${communityId}/import/${batchId}/confirm`,
    { skipInvalidRows },
  )
  return data
}
