import { api } from '@/lib/axios'
import type { PublicPayView } from '@/types'

export async function fetchPublicPay(token: string): Promise<PublicPayView> {
  const { data } = await api.get<PublicPayView>(`/public/pay/${encodeURIComponent(token)}`)
  return data
}
