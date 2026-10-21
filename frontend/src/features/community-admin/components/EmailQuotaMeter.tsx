import { cn } from '@/shared/utils/cn'
import { useCommunityPlan } from '../hooks/usePlan'
import { usagePercent } from '../planUsage'

/** "120 of 500 emails used this month", with a bar. Nothing when the plan has no limit. */
export function EmailQuotaMeter() {
  const plan = useCommunityPlan()
  if (!plan.data) return null
  const used = plan.data.usage.emailsThisMonth
  const limit = plan.data.limits.emails_per_month ?? null
  const percent = usagePercent(used, limit)
  if (limit === null || percent === null) {
    return (
      <p className="text-sm text-muted">
        {used.toLocaleString('en-IN')} emails sent this month. The {plan.data.planName} plan has no
        limit.
      </p>
    )
  }
  const left = Math.max(0, limit - used)
  return (
    <div className="grid gap-1.5">
      <p className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
        <span>
          <span className="font-semibold tabular-nums">
            {used.toLocaleString('en-IN')} of {limit.toLocaleString('en-IN')}
          </span>{' '}
          emails used this month
        </span>
        <span className="text-muted">{left.toLocaleString('en-IN')} left</span>
      </p>
      <div
        role="progressbar"
        aria-label="Emails used this month"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        className="h-2 overflow-hidden rounded bg-brand-50"
      >
        <div
          className={cn('h-full', percent >= 90 ? 'bg-danger' : 'bg-brand-600')}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  )
}
