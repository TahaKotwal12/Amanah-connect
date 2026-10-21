import { api } from '@/lib/axios'
import type { SettingsView } from '@/types'

export async function fetchSettings(): Promise<SettingsView> {
  const { data } = await api.get<SettingsView>('/community/settings')
  return data
}
