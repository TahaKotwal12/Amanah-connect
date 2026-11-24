import { api } from '@/lib/axios'
import type { DivideExpenseRequest, DivideResult, SharesView, Uuid } from '@/types'

/** Divide an expense equally among members (or every active member) and email each their bill. */
export async function divideExpense(id: Uuid, body: DivideExpenseRequest): Promise<DivideResult> {
  const { data } = await api.post<DivideResult>(`/community/expenses/${id}/divide`, body)
  return data
}

/** Each member's share of an expense, with what has been paid, and the totals. */
export async function fetchShares(id: Uuid): Promise<SharesView> {
  const { data } = await api.get<SharesView>(`/community/expenses/${id}/shares`)
  return data
}
