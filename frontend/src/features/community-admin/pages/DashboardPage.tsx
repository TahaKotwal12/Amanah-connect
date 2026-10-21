import {
  Banknote,
  CircleAlert,
  Landmark,
  MessageSquareWarning,
  Scale,
  Users,
  Wallet,
} from 'lucide-react'
import { Suspense, lazy } from 'react'
import { Link } from 'react-router'
import { formatDate, formatDateTime } from '@/lib/dates'
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
import { useDashboard, useOverdueInvoices } from '../hooks/useDashboard'
import { useWriteAccess } from '../hooks/usePlan'

// Recharts is the heaviest dependency here: fetched after the numbers, not before.
const CollectionTrendChart = lazy(() => import('../components/CollectionTrendChart'))

const ICON = 'size-5'

function ChartSkeleton() {
  return <Skeleton className="h-80" role="status" aria-label="Loading the chart" />
}

/** A quick action: a link while writes are allowed, a disabled button with the reason when not. */
function QuickAction({
  to,
  label,
  allowed,
  reason,
}: {
  to: string
  label: string
  allowed: boolean
  reason: string | null
}) {
  if (allowed) {
    return (
      <Button asChild variant="secondary">
        <Link to={to}>{label}</Link>
      </Button>
    )
  }
  return (
    <Button
      variant="secondary"
      disabled
      aria-describedby="write-disabled-reason"
      title={reason ?? undefined}
    >
      {label}
    </Button>
  )
}

export default function DashboardPage() {
  const dashboard = useDashboard()
  const overdue = useOverdueInvoices()
  const write = useWriteAccess()
  const d = dashboard.data
  const money = (amount: string | undefined) =>
    amount === undefined ? '' : formatMoney(amount, { currency: d?.currency ?? 'INR' })
  const loading = dashboard.isLoading

  return (
    <>
      <PageHeader
        title="Dashboard"
        description={
          d ? `Where the community stands this month, ${d.month}.` : 'Where the community stands.'
        }
      />

      <section aria-label="Quick actions" className="mb-6 flex flex-wrap gap-2">
        <QuickAction
          to={`${paths.admin.members}?new=1`}
          label="Add member"
          allowed={write.allowed}
          reason={write.reason}
        />
        <QuickAction
          to={`${paths.admin.billing}?new=invoice`}
          label="Create invoice"
          allowed={write.allowed}
          reason={write.reason}
        />
        <QuickAction
          to={`${paths.admin.billing}?new=payment`}
          label="Record payment"
          allowed={write.allowed}
          reason={write.reason}
        />
        <QuickAction
          to={`${paths.admin.budget}?new=expense`}
          label="Add expense"
          allowed={write.allowed}
          reason={write.reason}
        />
        {!write.allowed ? (
          <p id="write-disabled-reason" className="basis-full text-sm text-muted">
            {write.reason}
          </p>
        ) : null}
      </section>

      {dashboard.isError ? (
        <ErrorState
          headingLevel={2}
          error={dashboard.error}
          onRetry={() => void dashboard.refetch()}
          retrying={dashboard.isFetching}
        />
      ) : (
        <section aria-label="Key numbers" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          <StatCard
            label="Active members"
            value={d?.members.active.toLocaleString('en-IN')}
            hint={d ? `${d.members.total.toLocaleString('en-IN')} in all` : undefined}
            icon={<Users className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Collected this month"
            value={money(d?.collection.receivedInMonth)}
            hint="Money that came in, after reversals"
            icon={<Wallet className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Outstanding"
            value={money(d?.collection.outstanding)}
            hint={d ? `Of ${money(d.collection.billed)} billed this month` : undefined}
            icon={<Banknote className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Overdue"
            value={money(d?.overdue.amount)}
            hint={
              d
                ? d.overdue.count === 1
                  ? '1 invoice is past its due date'
                  : `${d.overdue.count.toLocaleString('en-IN')} invoices are past their due date`
                : undefined
            }
            icon={<CircleAlert className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Net balance"
            value={money(d?.netBalance)}
            hint="Income less expenses"
            icon={<Scale className={ICON} />}
            loading={loading}
          />
          <StatCard
            label="Open complaints"
            value={d?.complaints.open}
            hint={
              d && d.complaints.slaBreached > 0
                ? `${d.complaints.slaBreached} past their response target`
                : undefined
            }
            icon={<MessageSquareWarning className={ICON} />}
            loading={loading}
          />
        </section>
      )}

      <section aria-label="Collection trend" className="mt-8">
        {d ? (
          <Suspense fallback={<ChartSkeleton />}>
            <CollectionTrendChart points={d.collectionTrend} currency={d.currency} />
          </Suspense>
        ) : dashboard.isError ? null : (
          <ChartSkeleton />
        )}
      </section>

      <div className="mt-8 grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle as="h2" className="text-lg">
              Overdue
            </CardTitle>
            <Button asChild variant="ghost" size="sm">
              <Link to={`${paths.admin.billing}?status=OVERDUE`}>See all</Link>
            </Button>
          </CardHeader>
          <CardContent>
            {overdue.isError ? (
              <ErrorState
                headingLevel={3}
                error={overdue.error}
                onRetry={() => void overdue.refetch()}
                retrying={overdue.isFetching}
              />
            ) : overdue.isLoading ? (
              <Skeleton className="h-40" role="status" aria-label="Loading overdue invoices" />
            ) : overdue.data && overdue.data.items.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <caption className="sr-only">Invoices past their due date</caption>
                  <thead>
                    <tr className="border-b border-brand-100 text-muted">
                      <th scope="col" className="py-2 pr-3 font-medium">
                        Member
                      </th>
                      <th scope="col" className="py-2 pr-3 font-medium">
                        Due
                      </th>
                      <th scope="col" className="py-2 text-right font-medium">
                        Owes
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {overdue.data.items.map((invoice) => (
                      <tr key={invoice.id} className="border-b border-brand-50">
                        <td className="py-2 pr-3">
                          <Link
                            to={paths.admin.member(invoice.memberId)}
                            className="font-medium text-brand-700 underline-offset-2 hover:underline"
                          >
                            {invoice.memberName}
                          </Link>
                          <span className="block text-xs text-muted">{invoice.invoiceNo}</span>
                        </td>
                        <td className="py-2 pr-3">{formatDate(invoice.dueDate)}</td>
                        <td className="py-2 text-right tabular-nums">
                          {formatMoney(invoice.balance, { currency: d?.currency ?? 'INR' })}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <EmptyState
                headingLevel={3}
                title="Nobody is overdue"
                description="Invoices past their due date appear here."
              />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle as="h2" className="text-lg">
              Recent activity
            </CardTitle>
          </CardHeader>
          <CardContent>
            {loading ? (
              <Skeleton className="h-40" role="status" aria-label="Loading activity" />
            ) : d && d.recentActivity.length > 0 ? (
              <ol className="relative grid gap-4 border-l border-brand-100 pl-5">
                {d.recentActivity.map((entry, index) => (
                  <li key={`${entry.at}-${index}`} className="relative">
                    <span
                      aria-hidden="true"
                      className="absolute -left-[1.6rem] top-1.5 size-2.5 rounded-full bg-brand-500"
                    />
                    <p className="font-medium text-brand-900">{entry.summary}</p>
                    <p className="text-sm text-muted">
                      {entry.actor}
                      {entry.byPlatformStaff ? ' (platform team)' : ''}, {formatDateTime(entry.at)}
                    </p>
                  </li>
                ))}
              </ol>
            ) : (
              <EmptyState headingLevel={3} title="No activity yet" />
            )}
          </CardContent>
        </Card>
      </div>

      {d && d.upcomingDues.count > 0 ? (
        <Card className="mt-4">
          <CardHeader>
            <CardTitle as="h2" className="flex items-center gap-2 text-lg">
              <Landmark aria-hidden="true" className="size-5 text-brand-600" />
              Falling due in the next {d.upcomingDues.days} days
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="mb-3 text-sm">
              {d.upcomingDues.count === 1
                ? '1 invoice'
                : `${d.upcomingDues.count.toLocaleString('en-IN')} invoices`}
              , {money(d.upcomingDues.amount)} in all. The soonest:
            </p>
            <ul className="divide-y divide-brand-100">
              {d.upcomingDues.items.map((item) => (
                <li
                  key={item.invoiceId}
                  className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm"
                >
                  <span>
                    <Link
                      to={paths.admin.member(item.memberId)}
                      className="font-medium text-brand-700 underline-offset-2 hover:underline"
                    >
                      {item.memberName}
                    </Link>
                    , {item.invoiceNo}, due {formatDate(item.dueDate)}
                  </span>
                  <span className="flex items-center gap-2">
                    <span className="tabular-nums">{money(item.balance)}</span>
                    <StatusBadge status={item.status} />
                  </span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      ) : null}
    </>
  )
}
