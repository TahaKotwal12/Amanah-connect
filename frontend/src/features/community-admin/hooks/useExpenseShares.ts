import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { DivideExpenseRequest, Uuid } from '@/types'
import { divideExpense, fetchShares } from '../api/expenseShares'
import { communityAdminKeys } from '../queryKeys'

export function useExpenseShares(id: Uuid | undefined) {
  return useQuery({
    queryKey: communityAdminKeys.expenseShares.detail(id ?? ''),
    queryFn: () => fetchShares(id as Uuid),
    enabled: id !== undefined,
    // Shares change when cash is recorded, which refreshes this key; no need to refetch on a timer.
    staleTime: 15_000,
  })
}

/** Dividing creates invoices (and queues emails), so everything that lists money is stale. */
export function useDivideExpense() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: DivideExpenseRequest }) => divideExpense(id, body),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: communityAdminKeys.expenseShares.all }),
        client.invalidateQueries({ queryKey: communityAdminKeys.invoices.all }),
        client.invalidateQueries({ queryKey: communityAdminKeys.dashboard() }),
        client.invalidateQueries({ queryKey: communityAdminKeys.members.all }),
      ]),
  })
}
