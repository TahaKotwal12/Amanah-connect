import { api } from '@/lib/axios'
import type { LeadStatus, LeadView, PageResponse, Uuid } from '@/types'
import type { LeadListParams } from '../queryKeys'
import { toQuery } from './params'

export async function fetchLeads(params: LeadListParams): Promise<PageResponse<LeadView>> {
  const { data } = await api.get<PageResponse<LeadView>>('/admin/leads', {
    params: toQuery(params),
  })
  return data
}

export async function fetchLead(id: Uuid): Promise<LeadView> {
  const { data } = await api.get<LeadView>(`/admin/leads/${id}`)
  return data
}

/** NEW, CONTACTED, DEMO_SCHEDULED or LOST. CONVERTED is set by creating the community from the lead. */
export async function updateLeadStatus(id: Uuid, status: LeadStatus): Promise<LeadView> {
  const { data } = await api.patch<LeadView>(`/admin/leads/${id}/status`, { status })
  return data
}
