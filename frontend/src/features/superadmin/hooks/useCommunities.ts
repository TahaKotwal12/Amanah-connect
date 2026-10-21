import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import type { CreateCommunityRequest, UpdateCommunityRequest, Uuid } from '@/types'
import {
  changeCommunityStatus,
  confirmImport,
  createCommunity,
  fetchCommunities,
  fetchCommunity,
  fetchCommunityOverview,
  fetchLeadDraft,
  fetchSubscriptionHistory,
  resetAdminPassword,
  updateCommunity,
  uploadImport,
  type CommunityAction,
  type ImportFiles,
} from '../api/communities'
import { fetchAudit } from '../api/audit'
import { fetchThreads } from '../api/support'
import {
  superadminKeys,
  type AuditParams,
  type CommunityListParams,
  type SupportListParams,
} from '../queryKeys'

export function useCommunities(params: CommunityListParams, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: superadminKeys.communities.list(params),
    queryFn: () => fetchCommunities(params),
    placeholderData: keepPreviousData,
    ...options,
  })
}

export function useCommunity(id: Uuid) {
  return useQuery({
    queryKey: superadminKeys.communities.detail(id),
    queryFn: () => fetchCommunity(id),
  })
}

/** Audited on the server as SUPPORT_VIEW every time it is fetched, so it is never refetched on focus. */
export function useCommunityOverview(id: Uuid) {
  return useQuery({
    queryKey: superadminKeys.communities.overview(id),
    queryFn: () => fetchCommunityOverview(id),
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    staleTime: Infinity,
  })
}

export function useSubscriptionHistory(id: Uuid) {
  return useQuery({
    queryKey: superadminKeys.communities.subscriptions(id),
    queryFn: () => fetchSubscriptionHistory(id),
  })
}

export function useCommunityActivity(id: Uuid) {
  const params: AuditParams = { communityId: id, limit: 25 }
  return useQuery({
    queryKey: superadminKeys.communities.activity(id),
    queryFn: () => fetchAudit(params),
  })
}

export function useCommunityThreads(id: Uuid) {
  const params: SupportListParams = { communityId: id, size: 20 }
  return useQuery({
    queryKey: superadminKeys.support.list(params),
    queryFn: () => fetchThreads(params),
  })
}

export function useLeadDraft(leadId: Uuid | null) {
  return useQuery({
    queryKey: superadminKeys.leads.detail(leadId ?? ''),
    queryFn: () => fetchLeadDraft(leadId ?? ''),
    enabled: leadId !== null,
    staleTime: Infinity,
  })
}

/** Anything that changes a community changes the lists, its detail and the dashboard numbers. */
function refresh(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: superadminKeys.communities.all }),
    client.invalidateQueries({ queryKey: superadminKeys.stats.all }),
  ])
}

export function useCreateCommunity() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCommunityRequest) => createCommunity(body),
    onSuccess: async () => {
      await Promise.all([
        refresh(client),
        client.invalidateQueries({ queryKey: superadminKeys.leads.all }),
      ])
    },
  })
}

export function useUpdateCommunity() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateCommunityRequest }) =>
      updateCommunity(id, body),
    onSuccess: () => refresh(client),
  })
}

/**
 * Suspend, activate or archive. Not optimistic: the server decides whether the change is allowed
 * (and emails the owner), so the screen shows what it answered.
 */
export function useChangeCommunityStatus() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action, reason }: { id: Uuid; action: CommunityAction; reason?: string }) =>
      changeCommunityStatus(id, action, reason),
    onSuccess: () => refresh(client),
  })
}

export function useResetAdminPassword() {
  return useMutation({ mutationFn: (id: Uuid) => resetAdminPassword(id) })
}

export function useUploadImport(communityId: Uuid) {
  return useMutation({
    mutationFn: ({ batchId, files }: { batchId: Uuid; files: ImportFiles }) =>
      uploadImport(communityId, batchId, files),
  })
}

export function useConfirmImport(communityId: Uuid) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, skipInvalidRows }: { batchId: Uuid; skipInvalidRows: boolean }) =>
      confirmImport(communityId, batchId, skipInvalidRows),
    onSuccess: () => refresh(client),
  })
}
