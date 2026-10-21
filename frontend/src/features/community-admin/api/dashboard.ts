import { api } from '@/lib/axios'
import type { Dashboard } from '@/types'

export async function fetchDashboard(): Promise<Dashboard> {
  const { data } = await api.get<Dashboard>('/community/dashboard')
  return data
}
