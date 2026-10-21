import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { CreatePlanRequest, PlanView, UpdatePlanRequest, Uuid } from '@/types'
import { createPlan, fetchPlans, setPlanActive, updatePlan } from '../api/plans'
import { superadminKeys } from '../queryKeys'

export function usePlans() {
  return useQuery({
    queryKey: superadminKeys.plans.lists(),
    queryFn: fetchPlans,
    placeholderData: keepPreviousData,
  })
}

/** Plans also feed the community wizard and the pricing page, so every mutation refreshes the lot. */
function useRefreshPlans() {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: superadminKeys.plans.all })
}

export function useCreatePlan() {
  const refresh = useRefreshPlans()
  return useMutation({
    mutationFn: (body: CreatePlanRequest) => createPlan(body),
    onSuccess: refresh,
  })
}

export function useUpdatePlan() {
  const refresh = useRefreshPlans()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdatePlanRequest }) => updatePlan(id, body),
    onSuccess: refresh,
  })
}

/**
 * Activate or deactivate. Not optimistic: the server refuses to deactivate a plan in some cases,
 * and a card that flips and flips back reads as a bug.
 */
export function useSetPlanActive() {
  const refresh = useRefreshPlans()
  return useMutation({
    mutationFn: ({ plan, active }: { plan: PlanView; active: boolean }) =>
      setPlanActive(plan.id, active),
    onSuccess: refresh,
  })
}
