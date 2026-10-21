import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type {
  AddCommentRequest,
  AssigneeView,
  ChangeComplaintStatusRequest,
  CommentView,
  ComplaintCounts,
  ComplaintView,
  CreateComplaintRequest,
  PageResponse,
  UpdateComplaintRequest,
  Uuid,
} from '@/types'
import type { ComplaintListParams } from '../queryKeys'

export async function fetchComplaints(
  params: ComplaintListParams,
): Promise<PageResponse<ComplaintView>> {
  const { data } = await api.get<PageResponse<ComplaintView>>('/community/complaints', {
    params: toQuery(params),
  })
  return data
}

export async function fetchComplaintCounts(): Promise<ComplaintCounts> {
  const { data } = await api.get<ComplaintCounts>('/community/complaints/counts')
  return data
}

export async function fetchAssignees(): Promise<AssigneeView[]> {
  const { data } = await api.get<AssigneeView[]>('/community/complaints/assignees')
  return data
}

export async function fetchComplaint(id: Uuid): Promise<ComplaintView> {
  const { data } = await api.get<ComplaintView>(`/community/complaints/${id}`)
  return data
}

export async function createComplaint(body: CreateComplaintRequest): Promise<ComplaintView> {
  const { data } = await api.post<ComplaintView>('/community/complaints', body)
  return data
}

export async function updateComplaint(
  id: Uuid,
  body: UpdateComplaintRequest,
): Promise<ComplaintView> {
  const { data } = await api.patch<ComplaintView>(`/community/complaints/${id}`, body)
  return data
}

export async function changeComplaintStatus(
  id: Uuid,
  body: ChangeComplaintStatusRequest,
): Promise<ComplaintView> {
  const { data } = await api.post<ComplaintView>(`/community/complaints/${id}/status`, body)
  return data
}

export async function fetchComments(id: Uuid): Promise<CommentView[]> {
  const { data } = await api.get<CommentView[]>(`/community/complaints/${id}/comments`)
  return data
}

export async function addComment(id: Uuid, body: AddCommentRequest): Promise<CommentView> {
  const { data } = await api.post<CommentView>(`/community/complaints/${id}/comments`, body)
  return data
}
