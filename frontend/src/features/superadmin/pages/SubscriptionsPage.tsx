import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { formatDate } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { SimpleSelect } from '@/shared/components/ui/select'
import type { SubscriptionView } from '@/types'
import { ExpiryBadge } from '../components/ExpiryBadge'
import { ReasonDialog } from '@/shared/components/ui/reason-dialog'
import { RecordPaymentSheet } from '../components/RecordPaymentSheet'
import { useListState } from '@/shared/hooks/useListState'
import { useCancelSubscription, useSubscriptions } from '../hooks/useSubscriptions'

const FILTERS = ['status', 'days'] as const
const DEFAULT_SORT = ['periodEnd,asc'] as const

const ALL = 'all'
const STATUS_OPTIONS = [
  { value: ALL, label: 'Any status' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'EXPIRING', label: 'Ending soon' },
  { value: 'EXPIRED', label: 'Ended' },
  { value: 'CANCELLED', label: 'Cancelled' },
]
const DAYS_OPTIONS = [
  { value: ALL, label: 'Any end date' },
  { value: '7', label: 'Ending in 7 days' },
  { value: '15', label: 'Ending in 15 days' },
  { value: '30', label: 'Ending in 30 days' },
  { value: '60', label: 'Ending in 60 days' },
  { value: '90', label: 'Ending in 90 days' },
]

const col = createDataTableColumns<SubscriptionView>()

export default function SubscriptionsPage() {
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { status, days } = list.filters
  const daysNumber = days ? Number(days) : undefined
  const query = useSubscriptions({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...(status ? { status } : {}),
    ...(daysNumber ? { expiringInDays: daysNumber } : {}),
  })
  const cancel = useCancelSubscription()
  const [paymentOpen, setPaymentOpen] = useState(false)
  const [cancelling, setCancelling] = useState<SubscriptionView | null>(null)

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('communityName', {
          id: 'communityName',
          header: 'Community',
          cell: (info) => (
            <Link
              to={paths.superadmin.community(info.row.original.communityId)}
              className="font-medium text-brand-700 underline-offset-2 hover:underline"
            >
              {info.getValue()}
            </Link>
          ),
        }),
        col.accessor('planName', { header: 'Plan', enableSorting: false }),
        col.accessor('periodStart', {
          id: 'periodStart',
          header: 'Starts',
          cell: (info) => formatDate(info.getValue()),
        }),
        col.accessor('periodEnd', {
          id: 'periodEnd',
          header: 'Ends',
          cell: (info) => formatDate(info.getValue()),
        }),
        col.accessor('status', {
          header: 'Status',
          enableSorting: false,
          cell: (info) => (
            <ExpiryBadge status={info.getValue()} daysLeft={info.row.original.daysLeft} />
          ),
        }),
        col.accessor('amount', {
          id: 'amount',
          header: 'Amount',
          cell: (info) => <span className="tabular-nums">{formatMoney(info.getValue())}</span>,
        }),
        col.accessor('paidOn', {
          id: 'paidOn',
          header: 'Received',
          cell: (info) => formatDate(info.getValue()),
        }),
        col.accessor('reference', {
          header: 'Reference',
          enableSorting: false,
          cell: (info) => info.getValue() ?? '',
        }),
      ]),
    [],
  )

  const filtering = status !== '' || days !== ''

  return (
    <>
      <PageHeader
        title="Subscriptions"
        description="Every payment a community has made for its plan."
        actions={<Button onClick={() => setPaymentOpen(true)}>Record payment</Button>}
      />

      <DataTable
        label="Subscriptions"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(row) => row.id}
        rowCount={query.data?.total ?? 0}
        pagination={list.pagination}
        onPaginationChange={list.setPagination}
        sorting={list.sorting}
        onSortingChange={list.setSorting}
        isLoading={query.isLoading}
        isFetching={query.isFetching}
        error={query.error}
        onRetry={() => void query.refetch()}
        filters={
          <>
            <SimpleSelect
              aria-label="Filter by status"
              options={STATUS_OPTIONS}
              value={status || ALL}
              onValueChange={(value) => list.setFilter('status', value === ALL ? '' : value)}
              className="w-44"
            />
            <SimpleSelect
              aria-label="Filter by end date"
              options={DAYS_OPTIONS}
              value={days || ALL}
              onValueChange={(value) => list.setFilter('days', value === ALL ? '' : value)}
            />
          </>
        }
        rowLabel={(row) => `${row.communityName}, ${row.planName}`}
        rowActions={(row) =>
          row.status === 'CANCELLED' ? null : (
            <DropdownMenuItem destructive onSelect={() => setCancelling(row)}>
              Cancel subscription
            </DropdownMenuItem>
          )
        }
        emptyState={
          filtering ? (
            <EmptyState
              title="No subscriptions match"
              description="Try a different status or end date."
              action={
                <Button
                  variant="secondary"
                  onClick={() => {
                    list.setFilter('status', '')
                    list.setFilter('days', '')
                  }}
                >
                  Clear filters
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No payments recorded yet"
              description="Record a payment to start a community's subscription period."
              action={<Button onClick={() => setPaymentOpen(true)}>Record payment</Button>}
            />
          )
        }
      />

      <RecordPaymentSheet open={paymentOpen} onOpenChange={setPaymentOpen} />

      <ReasonDialog
        open={cancelling !== null}
        onOpenChange={(open) => !open && setCancelling(null)}
        title="Cancel this subscription?"
        description={
          cancelling ? (
            <p>
              {cancelling.communityName}, {cancelling.planName},{' '}
              {formatDate(cancelling.periodStart)} to {formatDate(cancelling.periodEnd)}. The record
              stays in the list as cancelled and no longer counts as revenue.
            </p>
          ) : (
            ''
          )
        }
        confirmLabel="Cancel subscription"
        onSubmit={async (reason) => {
          if (!cancelling) return
          await cancel.mutateAsync({ id: cancelling.id, reason })
          notify.success('Subscription cancelled')
        }}
      />
    </>
  )
}
