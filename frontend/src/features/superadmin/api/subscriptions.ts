import { api } from '@/lib/axios'
import type {
  CancelSubscriptionRequest,
  PageResponse,
  RecordSubscriptionRequest,
  SubscriptionView,
  Uuid,
} from '@/types'
import { toQuery } from './params'
import type { SubscriptionListParams } from '../queryKeys'

export async function fetchSubscriptions(
  params: SubscriptionListParams,
): Promise<PageResponse<SubscriptionView>> {
  const { expiringInDays, status, ...paging } = params
  // "Ending in N days" is its own endpoint (it also lists what has already ended) and takes no
  // status filter; the plain list takes one.
  const path = expiringInDays ? '/admin/subscriptions/expiring' : '/admin/subscriptions'
  const query = expiringInDays ? { ...paging, days: expiringInDays } : { ...paging, status }
  const { data } = await api.get<PageResponse<SubscriptionView>>(path, { params: toQuery(query) })
  return data
}

export async function recordSubscription(
  body: RecordSubscriptionRequest,
): Promise<SubscriptionView> {
  const { data } = await api.post<SubscriptionView>('/admin/subscriptions', body)
  return data
}

export async function cancelSubscription(
  id: Uuid,
  body: CancelSubscriptionRequest,
): Promise<SubscriptionView> {
  const { data } = await api.post<SubscriptionView>(`/admin/subscriptions/${id}/cancel`, body)
  return data
}
