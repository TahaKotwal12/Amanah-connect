import { create } from 'zustand'
import type { ApiError } from '@/lib/apiError'

/** What the server said when a plan limit stopped an action (PLAN_LIMIT_EXCEEDED, HTTP 402). */
export interface PlanLimitNotice {
  message: string
  /** members, storage, emails: the plan limit key, e.g. max_members. */
  limitKey: string | null
  limit: number | null
  current: number | null
  plan: string | null
}

interface PlanLimitState {
  notice: PlanLimitNotice | null
  show: (notice: PlanLimitNotice) => void
  dismiss: () => void
}

export const usePlanLimitStore = create<PlanLimitState>()((set) => ({
  notice: null,
  show: (notice) => set({ notice }),
  dismiss: () => set({ notice: null }),
}))

const asNumber = (value: unknown): number | null => (typeof value === 'number' ? value : null)

export function noticeFromError(error: ApiError): PlanLimitNotice {
  const { extras } = error
  return {
    message: error.detail,
    limitKey: typeof extras.limit === 'string' ? extras.limit : null,
    limit: asNumber(extras.limitValue),
    current: asNumber(extras.current),
    plan: typeof extras.plan === 'string' ? extras.plan : null,
  }
}
