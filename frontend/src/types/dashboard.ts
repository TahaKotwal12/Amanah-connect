import type { IsoDate, IsoInstant, MoneyString, Uuid } from './common'

export interface DashboardDueItem {
  invoiceId: Uuid
  invoiceNo: string
  memberId: Uuid
  memberName: string
  dueDate: IsoDate
  balance: MoneyString
  status: string
}
export interface DashboardActivity {
  at: IsoInstant
  summary: string
  actor: string
  byPlatformStaff: boolean
  action: string
}
export interface TrendPoint {
  /** yyyy-MM */
  month: string
  billed: MoneyString
  collected: MoneyString
}
/** GET /dashboard (community admin). */
export interface Dashboard {
  generatedAt: IsoInstant
  currency: string
  month: string
  members: { total: number; active: number }
  collection: {
    billed: MoneyString
    collected: MoneyString
    outstanding: MoneyString
    receivedInMonth: MoneyString
  }
  overdue: { count: number; amount: MoneyString }
  netBalance: MoneyString
  complaints: { open: number; slaBreached: number }
  unreadSupportMessages: number
  upcomingDues: { days: number; count: number; amount: MoneyString; items: DashboardDueItem[] }
  recentActivity: DashboardActivity[]
  collectionTrend: TrendPoint[]
}
