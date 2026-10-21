import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryKey,
} from '@tanstack/react-query'
import type { LeadStatus, LeadView, PageResponse, Uuid } from '@/types'
import { fetchLead, fetchLeads, updateLeadStatus } from '../api/leads'
import { superadminKeys, type LeadListParams } from '../queryKeys'

export function useLeads(params: LeadListParams) {
  return useQuery({
    queryKey: superadminKeys.leads.list(params),
    queryFn: () => fetchLeads(params),
    placeholderData: keepPreviousData,
  })
}

export function useLead(id: Uuid | null) {
  return useQuery({
    queryKey: superadminKeys.leads.detail(id ?? ''),
    queryFn: () => fetchLead(id ?? ''),
    enabled: id !== null,
  })
}

interface StatusChange {
  id: Uuid
  status: LeadStatus
}

/**
 * Moving a lead along the pipeline is safe to show at once: it is one field, any status but
 * CONVERTED is allowed, and a failure puts the old status back. Every cached list is patched, then
 * refetched, because a filtered list may no longer contain the lead.
 */
export function useChangeLeadStatus() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, status }: StatusChange) => updateLeadStatus(id, status),
    onMutate: async ({ id, status }) => {
      await client.cancelQueries({ queryKey: superadminKeys.leads.all })
      const snapshots: Array<[QueryKey, unknown]> = client.getQueriesData({
        queryKey: superadminKeys.leads.all,
      })
      const patch = (lead: LeadView): LeadView => (lead.id === id ? { ...lead, status } : lead)
      client.setQueriesData<PageResponse<LeadView>>(
        { queryKey: superadminKeys.leads.lists() },
        (page) => (page ? { ...page, items: page.items.map(patch) } : page),
      )
      client.setQueryData<LeadView>(superadminKeys.leads.detail(id), (lead) =>
        lead ? patch(lead) : lead,
      )
      return { snapshots }
    },
    onError: (_error, _change, context) => {
      for (const [key, data] of context?.snapshots ?? []) client.setQueryData(key, data)
    },
    onSettled: () => client.invalidateQueries({ queryKey: superadminKeys.leads.all }),
  })
}
