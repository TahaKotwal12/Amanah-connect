import { api } from '@/lib/axios'
import { toQuery } from '@/lib/query'
import type {
  ActivateRequest,
  ComplaintView,
  CreateMemberRequest,
  ImportReport,
  InvoiceView,
  MemberCommunication,
  MemberCounts,
  MemberView,
  PageResponse,
  PaymentView,
  UpdateMemberRequest,
  Uuid,
} from '@/types'
import type { MemberListParams } from '../queryKeys'

export async function fetchMembers(params: MemberListParams): Promise<PageResponse<MemberView>> {
  const { data } = await api.get<PageResponse<MemberView>>('/community/members', {
    params: toQuery(params),
  })
  return data
}

export async function fetchMember(id: Uuid): Promise<MemberView> {
  const { data } = await api.get<MemberView>(`/community/members/${id}`)
  return data
}

export async function fetchMemberCounts(): Promise<MemberCounts> {
  const { data } = await api.get<MemberCounts>('/community/members/counts')
  return data
}

export async function createMember(body: CreateMemberRequest): Promise<MemberView> {
  const { data } = await api.post<MemberView>('/community/members', body)
  return data
}

export async function updateMember(id: Uuid, body: UpdateMemberRequest): Promise<MemberView> {
  const { data } = await api.patch<MemberView>(`/community/members/${id}`, body)
  return data
}

export async function deactivateMember(id: Uuid, reason: string): Promise<MemberView> {
  const { data } = await api.post<MemberView>(`/community/members/${id}/deactivate`, { reason })
  return data
}

export async function activateMember(id: Uuid, body: ActivateRequest): Promise<MemberView> {
  const { data } = await api.post<MemberView>(`/community/members/${id}/activate`, body)
  return data
}

/** The member list as a CSV file, with the same filters as the screen. */
export async function exportMembers(
  filters: Pick<MemberListParams, 'q' | 'status' | 'group'>,
): Promise<Blob> {
  const { data } = await api.get<Blob>('/community/members/export', {
    params: toQuery(filters),
    responseType: 'blob',
  })
  return data
}

// ---- what a member's page shows ---------------------------------------------------------------

export async function fetchMemberInvoices(memberId: Uuid): Promise<PageResponse<InvoiceView>> {
  const { data } = await api.get<PageResponse<InvoiceView>>('/community/invoices', {
    params: toQuery({ memberId, size: 100, sort: ['dueDate,desc'] }),
  })
  return data
}

export async function fetchMemberPayments(memberId: Uuid): Promise<PageResponse<PaymentView>> {
  const { data } = await api.get<PageResponse<PaymentView>>('/community/payments', {
    params: toQuery({ memberId, size: 100 }),
  })
  return data
}

export async function fetchMemberComplaints(memberId: Uuid): Promise<PageResponse<ComplaintView>> {
  const { data } = await api.get<PageResponse<ComplaintView>>('/community/complaints', {
    params: toQuery({ memberId, size: 50 }),
  })
  return data
}

export async function fetchMemberCommunications(memberId: Uuid): Promise<MemberCommunication[]> {
  const { data } = await api.get<MemberCommunication[]>(
    `/community/members/${memberId}/communications`,
  )
  return data
}

// ---- CSV import -------------------------------------------------------------------------------

/** Dry run: every row is checked and nothing is saved. A repeat of the same upload is recognised. */
export async function uploadMemberImport(batchId: Uuid, file: File): Promise<ImportReport> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await api.post<ImportReport>('/community/members/import', form, {
    params: { batchId },
    headers: { 'Content-Type': undefined },
  })
  return data
}

export async function confirmMemberImport(
  batchId: Uuid,
  skipInvalidRows: boolean,
): Promise<ImportReport> {
  const { data } = await api.post<ImportReport>(`/community/members/import/${batchId}/confirm`, {
    skipInvalidRows,
  })
  return data
}
