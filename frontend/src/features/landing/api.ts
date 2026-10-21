import { api } from '@/lib/axios'
import { isMoneyString } from '@/lib/money'
import type { MoneyString, PublicLeadRequest, PublicLeadResponse } from '@/types'

/**
 * One plan from GET /api/v1/public/plans (backend: PublicPlanController, PublicPlanView). Only
 * active plans marked public are returned, in display order. Every field is still checked before
 * use, so a malformed answer falls back to the page's static copy instead of showing nonsense.
 */
export interface PublicPlan {
  code: string
  name: string
  priceMonthly: MoneyString
  priceYearly: MoneyString
  /** e.g. { max_members: 100, storage_mb: 500, emails_per_month: 500 }; null means unlimited. */
  limits: Record<string, number | null>
  /** e.g. { upi_qr: true, pdf_reports: false } */
  features: Record<string, boolean>
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {}
}

function parsePlan(raw: unknown): PublicPlan | null {
  const plan = asRecord(raw)
  const { code, name, priceMonthly, priceYearly } = plan
  if (typeof code !== 'string' || typeof name !== 'string') return null
  if (typeof priceMonthly !== 'string' || !isMoneyString(priceMonthly)) return null
  if (typeof priceYearly !== 'string' || !isMoneyString(priceYearly)) return null

  const limits: PublicPlan['limits'] = {}
  for (const [key, value] of Object.entries(asRecord(plan.limits))) {
    if (value === null || typeof value === 'number') limits[key] = value
  }
  const features: PublicPlan['features'] = {}
  for (const [key, value] of Object.entries(asRecord(plan.features))) {
    if (typeof value === 'boolean') features[key] = value
  }
  return { code, name, priceMonthly, priceYearly, limits, features }
}

/** Plans in display order. An empty list is a valid answer and shows the fallback. */
export async function fetchPublicPlans(): Promise<PublicPlan[]> {
  const { data } = await api.get<unknown>('/public/plans')
  const list = Array.isArray(data) ? data : asRecord(data).items
  if (!Array.isArray(list)) return []
  return list.map(parsePlan).filter((plan): plan is PublicPlan => plan !== null)
}

export async function submitLead(body: PublicLeadRequest): Promise<PublicLeadResponse> {
  const { data } = await api.post<PublicLeadResponse>('/public/leads', body)
  return data
}
