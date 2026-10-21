import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import type { ActivateRequest, CreateMemberRequest, UpdateMemberRequest, Uuid } from '@/types'
import {
  activateMember,
  confirmMemberImport,
  createMember,
  deactivateMember,
  fetchMember,
  fetchMemberCommunications,
  fetchMemberComplaints,
  fetchMemberCounts,
  fetchMemberInvoices,
  fetchMemberPayments,
  fetchMembers,
  updateMember,
  uploadMemberImport,
} from '../api/members'
import { communityAdminKeys, type MemberListParams } from '../queryKeys'

export function useMembers(params: MemberListParams) {
  return useQuery({
    queryKey: communityAdminKeys.members.list(params),
    queryFn: () => fetchMembers(params),
    placeholderData: keepPreviousData,
  })
}

export function useMember(id: Uuid) {
  return useQuery({
    queryKey: communityAdminKeys.members.detail(id),
    queryFn: () => fetchMember(id),
  })
}

export function useMemberCounts() {
  return useQuery({
    queryKey: communityAdminKeys.members.counts(),
    queryFn: fetchMemberCounts,
  })
}

export function useMemberStatement(id: Uuid) {
  return useQuery({
    queryKey: communityAdminKeys.members.statement(id),
    queryFn: () => fetchMemberInvoices(id),
  })
}

export function useMemberPayments(id: Uuid) {
  return useQuery({
    queryKey: communityAdminKeys.members.payments(id),
    queryFn: () => fetchMemberPayments(id),
  })
}

export function useMemberComplaints(id: Uuid) {
  return useQuery({
    queryKey: communityAdminKeys.members.complaints(id),
    queryFn: () => fetchMemberComplaints(id),
  })
}

export function useMemberCommunications(id: Uuid) {
  return useQuery({
    queryKey: communityAdminKeys.members.communications(id),
    queryFn: () => fetchMemberCommunications(id),
  })
}

/** A new member changes the lists, the counts, the plan-limit meter and the dashboard. */
function refresh(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: communityAdminKeys.members.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.plan() }),
    client.invalidateQueries({ queryKey: communityAdminKeys.dashboard() }),
  ])
}

export function useCreateMember() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateMemberRequest) => createMember(body),
    onSuccess: () => refresh(client),
  })
}

export function useUpdateMember() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateMemberRequest }) => updateMember(id, body),
    onSuccess: () => refresh(client),
  })
}

/** Activate and deactivate are not optimistic: the server refuses some of them (unpaid dues, limits). */
export function useDeactivateMember() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: Uuid; reason: string }) => deactivateMember(id, reason),
    onSuccess: () => refresh(client),
  })
}

export function useActivateMember() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: ActivateRequest }) => activateMember(id, body),
    onSuccess: () => refresh(client),
  })
}

export function useUploadMemberImport() {
  return useMutation({
    mutationFn: ({ batchId, file }: { batchId: Uuid; file: File }) =>
      uploadMemberImport(batchId, file),
  })
}

export function useConfirmMemberImport() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ batchId, skipInvalidRows }: { batchId: Uuid; skipInvalidRows: boolean }) =>
      confirmMemberImport(batchId, skipInvalidRows),
    onSuccess: () => refresh(client),
  })
}
