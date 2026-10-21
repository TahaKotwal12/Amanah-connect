import { useQuery } from '@tanstack/react-query'
import { fetchMonthlyStats, fetchStats } from '../api/stats'
import { superadminKeys } from '../queryKeys'

const DASHBOARD_STALE_MS = 60_000

export function useStats() {
  return useQuery({
    queryKey: superadminKeys.stats.summary(),
    queryFn: fetchStats,
    staleTime: DASHBOARD_STALE_MS,
  })
}

export function useMonthlyStats(months = 12) {
  return useQuery({
    queryKey: superadminKeys.stats.monthly(months),
    queryFn: () => fetchMonthlyStats(months),
    staleTime: DASHBOARD_STALE_MS,
  })
}
