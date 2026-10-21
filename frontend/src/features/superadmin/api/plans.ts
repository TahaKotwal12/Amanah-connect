import { api } from '@/lib/axios'
import type { CreatePlanRequest, PlanView, UpdatePlanRequest, Uuid } from '@/types'

/** Plans are few, so the endpoint returns a plain array (not a page). */
export async function fetchPlans(): Promise<PlanView[]> {
  const { data } = await api.get<PlanView[]>('/admin/plans')
  return data
}

export async function createPlan(body: CreatePlanRequest): Promise<PlanView> {
  const { data } = await api.post<PlanView>('/admin/plans', body)
  return data
}

export async function updatePlan(id: Uuid, body: UpdatePlanRequest): Promise<PlanView> {
  const { data } = await api.patch<PlanView>(`/admin/plans/${id}`, body)
  return data
}

export async function setPlanActive(id: Uuid, active: boolean): Promise<PlanView> {
  const { data } = await api.post<PlanView>(
    `/admin/plans/${id}/${active ? 'activate' : 'deactivate'}`,
  )
  return data
}
