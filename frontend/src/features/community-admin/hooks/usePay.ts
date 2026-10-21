import { useQuery } from '@tanstack/react-query'
import { fetchPublicPay } from '../api/pay'
import { communityAdminKeys } from '../queryKeys'

export function usePublicPay(token: string) {
  return useQuery({
    queryKey: communityAdminKeys.publicPay(token),
    queryFn: () => fetchPublicPay(token),
    retry: false,
    // The amount due changes when a payment is confirmed: always show the current figure.
    staleTime: 0,
    meta: { silent: true },
  })
}
