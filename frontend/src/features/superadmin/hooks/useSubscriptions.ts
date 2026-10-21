import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { RecordSubscriptionRequest, Uuid } from '@/types'
import { cancelSubscription, fetchSubscriptions, recordSubscription } from '../api/subscriptions'
import { superadminKeys, type SubscriptionListParams } from '../queryKeys'

export function useSubscriptions(params: SubscriptionListParams) {
  return useQuery({
    queryKey: superadminKeys.subscriptions.list(params),
    queryFn: () => fetchSubscriptions(params),
    placeholderData: keepPreviousData,
  })
}

/** A payment changes the lists, the dashboard numbers and the community's own plan tab. */
function useRefreshAfterPayment() {
  const client = useQueryClient()
  return async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: superadminKeys.subscriptions.all }),
      client.invalidateQueries({ queryKey: superadminKeys.stats.all }),
      client.invalidateQueries({ queryKey: superadminKeys.communities.all }),
    ])
  }
}

export function useRecordPayment() {
  const refresh = useRefreshAfterPayment()
  return useMutation({
    mutationFn: (body: RecordSubscriptionRequest) => recordSubscription(body),
    onSuccess: refresh,
  })
}

export function useCancelSubscription() {
  const refresh = useRefreshAfterPayment()
  return useMutation({
    mutationFn: ({ id, reason }: { id: Uuid; reason: string }) =>
      cancelSubscription(id, { reason }),
    onSuccess: refresh,
  })
}
