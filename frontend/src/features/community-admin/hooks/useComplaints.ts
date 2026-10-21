import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type {
  AddCommentRequest,
  ChangeComplaintStatusRequest,
  CreateComplaintRequest,
  UpdateComplaintRequest,
  Uuid,
} from '@/types'
import {
  addComment,
  changeComplaintStatus,
  createComplaint,
  fetchAssignees,
  fetchComments,
  fetchComplaint,
  fetchComplaintCounts,
  fetchComplaints,
  updateComplaint,
} from '../api/complaints'
import { communityAdminKeys, type ComplaintListParams } from '../queryKeys'

export function useComplaints(params: ComplaintListParams) {
  return useQuery({
    queryKey: communityAdminKeys.complaints.list(params),
    queryFn: () => fetchComplaints(params),
    placeholderData: keepPreviousData,
  })
}

export function useComplaintCounts() {
  return useQuery({
    queryKey: communityAdminKeys.complaints.counts(),
    queryFn: fetchComplaintCounts,
  })
}

export function useAssignees() {
  return useQuery({
    queryKey: communityAdminKeys.complaints.assignees(),
    queryFn: fetchAssignees,
    staleTime: 5 * 60_000,
  })
}

export function useComplaint(id: Uuid | null) {
  return useQuery({
    queryKey: communityAdminKeys.complaints.detail(id ?? ''),
    queryFn: () => fetchComplaint(id as Uuid),
    enabled: id !== null,
  })
}

export function useComments(id: Uuid | null) {
  return useQuery({
    queryKey: communityAdminKeys.complaints.comments(id ?? ''),
    queryFn: () => fetchComments(id as Uuid),
    enabled: id !== null,
  })
}

/** Lists, counts, the dashboard's open count and the member's own list all show complaints. */
function useRefreshComplaints() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: communityAdminKeys.complaints.all }),
      client.invalidateQueries({ queryKey: communityAdminKeys.dashboard() }),
      client.invalidateQueries({ queryKey: [...communityAdminKeys.members.all, 'detail'] }),
    ])
}

export function useCreateComplaint() {
  const refresh = useRefreshComplaints()
  return useMutation({
    mutationFn: (body: CreateComplaintRequest) => createComplaint(body),
    onSuccess: refresh,
  })
}

export function useUpdateComplaint() {
  const refresh = useRefreshComplaints()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateComplaintRequest }) =>
      updateComplaint(id, body),
    onSuccess: refresh,
  })
}

export function useChangeComplaintStatus() {
  const refresh = useRefreshComplaints()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: ChangeComplaintStatusRequest }) =>
      changeComplaintStatus(id, body),
    onSuccess: refresh,
  })
}

export function useAddComment() {
  const refresh = useRefreshComplaints()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: AddCommentRequest }) => addComment(id, body),
    onSuccess: refresh,
  })
}
