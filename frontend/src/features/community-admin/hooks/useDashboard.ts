import { useQuery } from '@tanstack/react-query'
import { fetchDashboard } from '../api/dashboard'
import { fetchOverdueInvoices } from '../api/invoices'
import { communityAdminKeys } from '../queryKeys'

export function useDashboard() {
  return useQuery({
    queryKey: communityAdminKeys.dashboard(),
    queryFn: fetchDashboard,
    staleTime: 30_000,
  })
}

export function useOverdueInvoices() {
  return useQuery({
    queryKey: communityAdminKeys.defaulters.list({ size: 5 }),
    queryFn: () => fetchOverdueInvoices(5),
    staleTime: 30_000,
  })
}
