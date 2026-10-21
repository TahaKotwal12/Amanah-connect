import { Eye } from 'lucide-react'
import { useParams } from 'react-router'
import { formatDate, formatDateTime } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { paths } from '@/routes/paths'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/components/ui/card'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { useCommunityOverview } from '../hooks/useCommunities'

function Count({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="font-heading text-2xl font-semibold lining-nums tabular-nums text-brand-900">
        {value.toLocaleString('en-IN')}
      </dd>
    </div>
  )
}

/**
 * A read-only look at what a community sees, for helping them. Opening it is recorded in the
 * audit trail, and the banner says so for as long as it is on screen.
 */
export default function CommunitySupportViewPage() {
  const { id = '' } = useParams()
  const overview = useCommunityOverview(id)
  const data = overview.data
  const currency = data?.community.currency ?? 'INR'

  return (
    <>
      <PageHeader
        title={data ? `${data.community.name}: support view` : 'Support view'}
        breadcrumbs={[
          { label: 'Communities', to: paths.superadmin.communities },
          { label: data?.community.name ?? 'Community', to: paths.superadmin.community(id) },
          { label: 'Support view' },
        ]}
      />

      <div
        role="note"
        className="mb-6 flex items-start gap-3 rounded border-2 border-gold-500 bg-gold-50 p-4 text-brand-900"
      >
        <Eye aria-hidden="true" className="mt-0.5 size-5 shrink-0" />
        <p className="font-medium">You are viewing community data (this access is logged).</p>
      </div>

      {overview.isError ? (
        <ErrorState
          headingLevel={2}
          error={overview.error}
          onRetry={() => void overview.refetch()}
          retrying={overview.isFetching}
        />
      ) : !data ? (
        <div role="status" aria-label="Loading community data" className="grid gap-4">
          <Skeleton className="h-40" />
          <Skeleton className="h-40" />
        </div>
      ) : (
        <div className="grid gap-4">
          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Profile
              </CardTitle>
            </CardHeader>
            <CardContent>
              <dl className="grid gap-4 sm:grid-cols-3">
                <div>
                  <dt className="text-sm text-muted">Status</dt>
                  <dd className="mt-0.5">
                    <StatusBadge status={data.community.status} />
                  </dd>
                </div>
                <div>
                  <dt className="text-sm text-muted">Plan</dt>
                  <dd className="mt-0.5 font-medium">{data.community.plan.name}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted">Administrator</dt>
                  <dd className="mt-0.5 font-medium">
                    {data.community.owner
                      ? `${data.community.owner.fullName}, ${data.community.owner.email}`
                      : 'None'}
                  </dd>
                </div>
              </dl>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Right now
              </CardTitle>
            </CardHeader>
            <CardContent>
              <dl className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-6">
                <Count label="Active members" value={data.counts.membersActive} />
                <Count label="Inactive members" value={data.counts.membersInactive} />
                <Count label="Registrations waiting" value={data.counts.pendingRegistrations} />
                <Count label="Open invoices" value={data.counts.openInvoices} />
                <Count label="Open complaints" value={data.counts.openComplaints} />
                <Count label="Open support threads" value={data.counts.openSupportThreads} />
              </dl>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Money, financial year from {formatDate(data.finance.financialYearStart)}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {(
                  [
                    ['Invoiced and open', data.finance.invoicedOpen],
                    ['Collected on those', data.finance.collectedOnOpenAndPaid],
                    ['Outstanding', data.finance.outstanding],
                    ['Income this year', data.finance.incomeThisFinancialYear],
                    ['Expense this year', data.finance.expenseThisFinancialYear],
                    ['Net this year', data.finance.netThisFinancialYear],
                  ] as const
                ).map(([label, amount]) => (
                  <div key={label}>
                    <dt className="text-sm text-muted">{label}</dt>
                    <dd className="font-medium tabular-nums">
                      {formatMoney(amount, { currency })}
                    </dd>
                  </div>
                ))}
              </dl>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Recent activity
              </CardTitle>
            </CardHeader>
            <CardContent>
              {data.recentActivity.length > 0 ? (
                <ol className="divide-y divide-brand-100">
                  {data.recentActivity.map((line) => (
                    <li key={line.id} className="py-3">
                      <p className="font-medium text-brand-900">{line.action}</p>
                      <p className="text-sm text-muted">
                        {line.actorName ?? 'System'}, {formatDateTime(line.at)}
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
      )}
    </>
  )
}
