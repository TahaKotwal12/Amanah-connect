import { Link } from 'react-router'
import { paths } from '@/routes/paths'
import { cn } from '@/shared/utils/cn'
import { useCommunityPlan } from '../hooks/usePlan'
import { UPGRADE_HINT_AT, usagePercent } from '../planUsage'

/** "43 of 50 members" with a bar, and from 90% a nudge toward a bigger plan. */
export function PlanMeter() {
  const plan = useCommunityPlan()
  if (!plan.data) return null
  const used = plan.data.usage.members
  const limit = plan.data.limits.max_members ?? null
  const percent = usagePercent(used, limit)
  const full = limit !== null && used >= limit
  const near = limit !== null && used / limit >= UPGRADE_HINT_AT

  return (
    <section
      aria-label="Plan limit for members"
      className="mb-4 rounded-card border border-brand-100 bg-white p-4"
    >
      {limit === null ? (
        <p className="text-sm">
          <span className="font-semibold tabular-nums">{used.toLocaleString('en-IN')}</span>{' '}
          members. The {plan.data.planName} plan has no limit.
        </p>
      ) : (
        <>
          <p className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
            <span>
              <span className="font-semibold tabular-nums">
                {used.toLocaleString('en-IN')} of {limit.toLocaleString('en-IN')}
              </span>{' '}
              members
            </span>
            <span className="text-muted">{plan.data.planName} plan</span>
          </p>
          <div
            role="progressbar"
            aria-label="Members used"
            aria-valuemin={0}
            aria-valuemax={limit}
            aria-valuenow={Math.min(used, limit)}
            aria-valuetext={`${used} of ${limit} members`}
            className="mt-2 h-2 overflow-hidden rounded bg-brand-50"
          >
            <div
              className={cn('h-full', full ? 'bg-danger' : near ? 'bg-warning' : 'bg-brand-600')}
              style={{ width: `${percent ?? 0}%` }}
            />
          </div>
          {near ? (
            <p className="mt-2 text-sm">
              {full
                ? 'You have reached the limit, so no more members can be added.'
                : 'You are close to the limit of your plan.'}{' '}
              <Link to={paths.admin.support} className="font-medium text-brand-700 underline">
                Ask about a bigger plan
              </Link>
            </p>
          ) : null}
        </>
      )}
    </section>
  )
}
