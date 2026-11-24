import { formatDate } from '@/lib/dates'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { cn } from '@/shared/utils/cn'
import { useCommunityPlan } from '../../hooks/usePlan'
import { UPGRADE_HINT_AT, usagePercent } from '../../planUsage'

const FEATURE_LABELS: Record<string, string> = {
  csv_export: 'Export to CSV',
  pdf_reports: 'PDF reports',
  upi_qr: 'UPI payment codes',
  email_reminders: 'Email reminders',
  custom_branding: 'Your own logo in emails',
}
const humanise = (key: string) =>
  FEATURE_LABELS[key] ?? key.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())

function Meter({
  label,
  used,
  limit,
  unit,
}: {
  label: string
  used: number
  limit: number | null
  unit: string
}) {
  const percent = usagePercent(used, limit)
  const full = limit !== null && used >= limit
  const near = limit !== null && used / limit >= UPGRADE_HINT_AT
  return (
    <li className="rounded-card border border-brand-100 bg-white p-4">
      <p className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
        <span className="font-medium text-ink">{label}</span>
        <span className="tabular-nums text-muted">
          {used.toLocaleString('en-IN')}
          {limit === null ? ` ${unit}, no limit` : ` of ${limit.toLocaleString('en-IN')} ${unit}`}
        </span>
      </p>
      {limit !== null ? (
        <div
          role="progressbar"
          aria-label={`${label} used`}
          aria-valuemin={0}
          aria-valuemax={limit}
          aria-valuenow={Math.min(used, limit)}
          aria-valuetext={`${used} of ${limit} ${unit}`}
          className="mt-2 h-2 overflow-hidden rounded bg-brand-50"
        >
          <div
            className={cn('h-full', full ? 'bg-danger' : near ? 'bg-warning' : 'bg-brand-600')}
            style={{ width: `${percent ?? 0}%` }}
          />
        </div>
      ) : null}
    </li>
  )
}

/** Which plan the community is on, how much of it is used, and when the paid period ends. */
export function PlanTab() {
  const plan = useCommunityPlan()
  if (plan.isLoading)
    return <Skeleton className="h-64" role="status" aria-label="Loading the plan" />
  if (plan.isError || !plan.data) {
    return <ErrorState error={plan.error} onRetry={() => void plan.refetch()} />
  }
  const p = plan.data
  const features = Object.entries(p.features)
  const sub = p.subscription
  return (
    <div className="grid max-w-3xl gap-8">
      <section aria-labelledby="plan-heading">
        <h2 id="plan-heading" className="font-heading text-lg font-semibold text-brand-900">
          {p.planName} plan
        </h2>
        {sub ? (
          <p className="mt-2 flex flex-wrap items-center gap-2 text-sm">
            <StatusBadge
              status={
                sub.status === 'ACTIVE'
                  ? 'active'
                  : sub.status === 'EXPIRING'
                    ? 'pending'
                    : 'overdue'
              }
              label={
                sub.status === 'ACTIVE'
                  ? 'Paid up'
                  : sub.status === 'EXPIRING'
                    ? 'Ending soon'
                    : 'Ended'
              }
            />
            <span>
              {formatDate(sub.periodStart)} to {formatDate(sub.periodEnd)}
              {sub.status === 'EXPIRED'
                ? ''
                : `, ${sub.daysLeft} ${sub.daysLeft === 1 ? 'day' : 'days'} left`}
            </span>
          </p>
        ) : (
          <p className="mt-2 text-sm text-muted">No paid period has been recorded yet.</p>
        )}
      </section>

      <section aria-labelledby="usage-heading">
        <h2 id="usage-heading" className="font-heading text-lg font-semibold text-brand-900">
          What you have used
        </h2>
        <ul className="mt-3 grid gap-3 sm:grid-cols-2">
          <Meter
            label="Members"
            used={p.usage.members}
            limit={p.limits.max_members ?? null}
            unit="members"
          />
          <Meter
            label="Emails this month"
            used={p.usage.emailsThisMonth}
            limit={p.limits.emails_per_month ?? null}
            unit="emails"
          />
        </ul>
      </section>

      {features.length > 0 ? (
        <section aria-labelledby="features-heading">
          <h2 id="features-heading" className="font-heading text-lg font-semibold text-brand-900">
            What is included
          </h2>
          <ul className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
            {features.map(([key, on]) => (
              <li key={key} className="flex items-center gap-2">
                <span aria-hidden="true">{on ? '✓' : '✗'}</span>
                <span className={on ? undefined : 'text-muted'}>
                  {humanise(key)}
                  <span className="sr-only">{on ? ', included' : ', not included'}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <p className="rounded bg-brand-50 px-4 py-3 text-sm text-brand-900">
        To change your plan, contact us from the Support page and we will set it up.
      </p>
    </div>
  )
}
