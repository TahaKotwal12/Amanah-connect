import {
  Building2,
  CalendarClock,
  CircleDollarSign,
  LifeBuoy,
  Mail,
  PauseCircle,
  Users,
} from 'lucide-react'
import { Suspense, lazy, useState } from 'react'
import { Link } from 'react-router'
import { formatDate, formatRelative } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/components/ui/card'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatCard } from '@/shared/components/ui/stat-card'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { ExpiryBadge } from '../components/ExpiryBadge'
import { RecordPaymentSheet } from '../components/RecordPaymentSheet'
import { useLeads } from '../hooks/useLeads'
import { useMonthlyStats, useStats } from '../hooks/useStats'
import { useSubscriptions } from '../hooks/useSubscriptions'

// Recharts is the heaviest dependency on this page: fetched after the numbers, not before.
const MonthlyCharts = lazy(() => import('../components/MonthlyCharts'))

const LIST_SIZE = 5
const ICON = 'size-5'

function ChartsSkeleton() {
  return (
    <div className="grid gap-4 lg:grid-cols-2" role="status" aria-label="Loading charts">
      <Skeleton className="h-80" />
      <Skeleton className="h-80" />
    </div>
  )
}

export default function DashboardPage() {
  const stats = useStats()
  const monthly = useMonthlyStats(12)
  const windowDays = stats.data?.expiringWithinDays ?? 30
  const expiring = useSubscriptions({
    expiringInDays: windowDays,
    size: LIST_SIZE,
    sort: ['periodEnd,asc'],
  })
  const leads = useLeads({ size: LIST_SIZE, sort: ['createdAt,desc'] })
  const [paymentOpen, setPaymentOpen] = useState(false)
  const s = stats.data
  const loading = stats.isLoading
  const money = (amount: string | undefined) =>
    amount === undefined
      ? ''
      : formatMoney(amount, { currency: s?.platformRevenue.currency ?? 'INR' })

  return (
    <>
      <PageHeader
        title="Platform dashboard"
        description="Communities, revenue and what needs attention."
        actions={
          <>
            <Button onClick={() => setPaymentOpen(true)}>Record payment</Button>
            <Button asChild variant="secondary">
              <Link to={`${paths.superadmin.communities}?new=1`}>New community</Link>
            </Button>
          </>
        }
      />

      {stats.isError ? (
        <ErrorState
          headingLevel={2}
          error={stats.error}
          onRetry={() => void stats.refetch()}
          retrying={stats.isFetching}
        />
      ) : (
        <section aria-label="Key numbers" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          <StatCard
            label="Communities"
            value={s?.communities.total}
            icon={<Building2 className={ICON} />}
            hint={
              s
                ? `${s.communities.pending} waiting to start, ${s.communities.archived} archived`
                : undefined
            }
            loading={loading}
          />
          <StatCard
            label="Active"
            value={s?.communities.active}
            icon={<Building2 className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Suspended"
            value={s?.communities.suspended}
            icon={<PauseCircle className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Members across communities"
            value={s?.totalMembers.toLocaleString('en-IN')}
            icon={<Users className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Revenue this month"
            value={money(s?.platformRevenue.thisMonth)}
            icon={<CircleDollarSign className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Revenue this year"
            value={money(s?.platformRevenue.thisYear)}
            icon={<CircleDollarSign className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Expiring soon"
            value={s?.expiringSubscriptions}
            icon={<CalendarClock className={ICON} />}
            hint={s ? `Subscriptions ending within ${s.expiringWithinDays} days` : undefined}
            loading={loading}
          />
          <StatCard
            label="New leads"
            value={s?.newLeads}
            icon={<Mail className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Open support"
            value={s?.openSupportThreads}
            icon={<LifeBuoy className={ICON} />}
            loading={loading}
          />
        </section>
      )}

      <section aria-label="Last 12 months" className="mt-8">
        {monthly.isError ? (
          <ErrorState
            headingLevel={2}
            title="The charts could not load"
            error={monthly.error}
            onRetry={() => void monthly.refetch()}
            retrying={monthly.isFetching}
          />
        ) : monthly.data ? (
          <Suspense fallback={<ChartsSkeleton />}>
            <MonthlyCharts series={monthly.data} />
          </Suspense>
        ) : (
          <ChartsSkeleton />
        )}
      </section>

      <div className="mt-8 grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle as="h2" className="text-lg">
              Subscriptions ending soon
            </CardTitle>
            <Button asChild variant="ghost" size="sm">
              <Link to={paths.superadmin.subscriptions}>See all</Link>
            </Button>
          </CardHeader>
          <CardContent>
            {expiring.isError ? (
              <ErrorState
                headingLevel={3}
                error={expiring.error}
                onRetry={() => void expiring.refetch()}
                retrying={expiring.isFetching}
              />
            ) : expiring.isLoading ? (
              <Skeleton className="h-40" role="status" aria-label="Loading subscriptions" />
            ) : expiring.data && expiring.data.items.length > 0 ? (
              <ul className="divide-y divide-brand-100">
                {expiring.data.items.map((subscription) => (
                  <li
                    key={subscription.id}
                    className="flex flex-wrap items-center justify-between gap-2 py-3"
                  >
                    <div className="min-w-0">
                      <Link
                        to={paths.superadmin.community(subscription.communityId)}
                        className="font-medium text-brand-700 underline-offset-2 hover:underline"
                      >
                        {subscription.communityName}
                      </Link>
                      <p className="text-sm text-muted">
                        {subscription.planName}, ends {formatDate(subscription.periodEnd)}
                      </p>
                    </div>
                    <ExpiryBadge status={subscription.status} daysLeft={subscription.daysLeft} />
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState
                headingLevel={3}
                title="Nothing is ending soon"
                description={`No subscription ends in the next ${windowDays} days.`}
              />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle as="h2" className="text-lg">
              Recent leads
            </CardTitle>
            <Button asChild variant="ghost" size="sm">
              <Link to={paths.superadmin.leads}>See all</Link>
            </Button>
          </CardHeader>
          <CardContent>
            {leads.isError ? (
              <ErrorState
                headingLevel={3}
                error={leads.error}
                onRetry={() => void leads.refetch()}
                retrying={leads.isFetching}
              />
            ) : leads.isLoading ? (
              <Skeleton className="h-40" role="status" aria-label="Loading leads" />
            ) : leads.data && leads.data.items.length > 0 ? (
              <ul className="divide-y divide-brand-100">
                {leads.data.items.map((lead) => (
                  <li
                    key={lead.id}
                    className="flex flex-wrap items-center justify-between gap-2 py-3"
                  >
                    <div className="min-w-0">
                      <p className="truncate font-medium text-brand-900">{lead.name}</p>
                      <p className="truncate text-sm text-muted">
                        {lead.communityName ?? lead.email}, {formatRelative(lead.createdAt)}
                      </p>
                    </div>
                    <StatusBadge status={lead.status} />
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState
                headingLevel={3}
                title="No leads yet"
                description="Requests from the website demo form appear here."
              />
            )}
          </CardContent>
        </Card>
      </div>

      <RecordPaymentSheet open={paymentOpen} onOpenChange={setPaymentOpen} />
    </>
  )
}
