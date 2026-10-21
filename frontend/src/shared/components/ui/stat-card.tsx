import { ArrowDownRight, ArrowRight, ArrowUpRight } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/shared/utils/cn'
import { Card } from './card'
import { Skeleton } from './skeleton'

export interface Trend {
  direction: 'up' | 'down' | 'flat'
  /** Already formatted: "12%" or "₹4,500.00". */
  value: string
  /** What it is compared with: "vs last month". */
  comparison?: string
  /** Is this direction good news? Collected money going up is good; overdue going up is not. */
  good?: boolean
}

interface StatCardProps {
  label: string
  /** Already formatted: use formatMoney for amounts. */
  value: ReactNode
  icon?: ReactNode
  trend?: Trend
  hint?: string
  loading?: boolean
  className?: string
}

const TREND_ICON = { up: ArrowUpRight, down: ArrowDownRight, flat: ArrowRight } as const
const TREND_WORD = { up: 'Up', down: 'Down', flat: 'No change' } as const

export function StatCard({ label, value, icon, trend, hint, loading, className }: StatCardProps) {
  if (loading) {
    return (
      <Card className={cn('p-5', className)} role="status" aria-label={`Loading ${label}`}>
        <Skeleton className="h-4 w-24" />
        <Skeleton className="mt-3 h-8 w-32" />
        <Skeleton className="mt-3 h-4 w-20" />
      </Card>
    )
  }
  const TrendIcon = trend ? TREND_ICON[trend.direction] : null
  return (
    <Card className={cn('p-5', className)}>
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm font-medium text-muted">{label}</p>
        {icon ? (
          <span aria-hidden="true" className="text-brand-500">
            {icon}
          </span>
        ) : null}
      </div>
      <p className="mt-2 font-heading text-3xl font-semibold lining-nums tabular-nums text-brand-900">
        {value}
      </p>
      {trend && TrendIcon ? (
        <p
          className={cn(
            'mt-2 flex flex-wrap items-center gap-1 text-sm font-medium',
            trend.good === undefined
              ? 'text-muted'
              : trend.good
                ? 'text-success-700'
                : 'text-danger',
          )}
        >
          <TrendIcon aria-hidden="true" className="size-4" />
          <span>
            <span className="sr-only">{TREND_WORD[trend.direction]} </span>
            {trend.value}
          </span>
          {trend.comparison ? (
            <span className="font-normal text-muted">{trend.comparison}</span>
          ) : null}
        </p>
      ) : null}
      {hint ? <p className="mt-2 text-sm text-muted">{hint}</p> : null}
    </Card>
  )
}
