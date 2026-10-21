import { api } from '@/lib/axios'
import type { MonthlySeries, PlatformStats } from '@/types'

export async function fetchStats(): Promise<PlatformStats> {
  const { data } = await api.get<PlatformStats>('/admin/stats')
  return data
}

export async function fetchMonthlyStats(months = 12): Promise<MonthlySeries> {
  const { data } = await api.get<MonthlySeries>('/admin/stats/monthly', { params: { months } })
  return data
}
