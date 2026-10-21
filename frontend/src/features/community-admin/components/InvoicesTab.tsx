import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { formatDate } from '@/lib/dates'
import { formatMoney, isZeroMoney } from '@/lib/money'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { useListState } from '@/shared/hooks/useListState'
import { cn } from '@/shared/utils/cn'
import type { InvoiceStatus, InvoiceView } from '@/types'
import { useInvoiceTotals, useInvoices } from '../hooks/useBilling'
import { useWriteAccess } from '../hooks/usePlan'
import { isPayable } from '../invoiceStatus'
import { InvoiceActionDialogs, type InvoiceActionTarget } from './InvoiceActionDialogs'

const FILTERS = ['status', 'q', 'dueFrom', 'dueTo'] as const
const DEFAULT_SORT = ['dueDate,desc'] as const

const STATUS_TABS: ReadonlyArray<{ value: InvoiceStatus | ''; label: string }> = [
  { value: '', label: 'All' },
  { value: 'ISSUED', label: 'Issued' },
  { value: 'PARTIAL', label: 'Partly paid' },
  { value: 'PAID', label: 'Paid' },
  { value: 'OVERDUE', label: 'Overdue' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

const col = createDataTableColumns<InvoiceView>()

export function InvoicesTab({ currency = 'INR' }: { currency?: string }) {
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { status, q, dueFrom, dueTo } = list.filters
  const write = useWriteAccess()
  const filters = {
    ...(status ? { status: status as InvoiceStatus } : {}),
    ...(q ? { q } : {}),
    ...(dueFrom ? { dueFrom } : {}),
    ...(dueTo ? { dueTo } : {}),
  }
  const query = useInvoices({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...filters,
  })
  const totals = useInvoiceTotals(filters)
  const [action, setAction] = useState<InvoiceActionTarget | null>(null)

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('invoiceNo', {
          id: 'invoiceNo',
          header: 'Invoice',
          cell: (info) => (
            <Link
              to={paths.admin.invoice(info.row.original.id)}
              className="font-medium text-brand-700 underline-offset-2 hover:underline"
            >
              {info.getValue()}
            </Link>
          ),
        }),
        col.accessor('memberName', {
          id: 'memberName',
          header: 'Member',
          cell: (info) => (
            <Link
              to={paths.admin.member(info.row.original.memberId)}
              className="text-brand-700 underline-offset-2 hover:underline"
            >
              {info.getValue()}
            </Link>
          ),
        }),
        col.accessor('description', {
          header: 'For',
          enableSorting: false,
          cell: (info) => {
            const period = info.row.original.period
            return period ? `${info.getValue()}, ${period}` : info.getValue()
          },
        }),
        col.accessor('dueDate', {
          id: 'dueDate',
          header: 'Due',
          cell: (info) => formatDate(info.getValue()),
        }),
        col.accessor('amount', {
          id: 'amount',
          header: 'Amount',
          cell: (info) => (
            <span className="tabular-nums">{formatMoney(info.getValue(), { currency })}</span>
          ),
        }),
        col.accessor('amountPaid', {
          header: 'Paid',
          enableSorting: false,
          cell: (info) => (
            <span className="tabular-nums">{formatMoney(info.getValue(), { currency })}</span>
          ),
        }),
        col.accessor('balance', {
          header: 'Balance',
          enableSorting: false,
          cell: (info) => (
            <span className="tabular-nums">{formatMoney(info.getValue(), { currency })}</span>
          ),
        }),
        col.accessor('status', {
          id: 'status',
          header: 'Status',
          cell: (info) => <StatusBadge status={info.getValue()} />,
        }),
      ]),
    [currency],
  )

  const filtering = status !== '' || q !== '' || dueFrom !== '' || dueTo !== ''

  return (
    <>
      <div role="group" aria-label="Show invoices" className="mb-4 flex flex-wrap gap-2">
        {STATUS_TABS.map((tab) => {
          const selected = tab.value === status
          return (
            <button
              key={tab.label}
              type="button"
              aria-pressed={selected}
              onClick={() => list.setFilter('status', tab.value)}
              className={cn(
                'inline-flex min-h-11 items-center rounded border px-3 text-sm font-medium',
                selected
                  ? 'border-brand-600 bg-brand-600 text-white'
                  : 'border-brand-200 bg-white text-brand-900 hover:border-brand-500',
              )}
            >
              {tab.label}
            </button>
          )
        })}
      </div>

      <DataTable
        label="Invoices"
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
        search={{
          value: q,
          onSearch: (value) => list.setFilter('q', value),
          placeholder: 'Search member or invoice number',
          label: 'Search invoices',
        }}
        filters={
          <>
            <div className="flex items-center gap-2">
              <label htmlFor="inv-due-from" className="text-sm text-muted">
                Due from
              </label>
              <DatePicker
                id="inv-due-from"
                value={dueFrom || undefined}
                onValueChange={(value) => list.setFilter('dueFrom', value ?? '')}
                {...(dueTo ? { max: dueTo } : {})}
                className="w-44"
              />
            </div>
            <div className="flex items-center gap-2">
              <label htmlFor="inv-due-to" className="text-sm text-muted">
                to
              </label>
              <DatePicker
                id="inv-due-to"
                value={dueTo || undefined}
                onValueChange={(value) => list.setFilter('dueTo', value ?? '')}
                {...(dueFrom ? { min: dueFrom } : {})}
                className="w-44"
              />
            </div>
          </>
        }
        rowLabel={(row) => row.invoiceNo}
        rowActions={(row) => (
          <>
            <DropdownMenuItem asChild>
              <Link to={paths.admin.invoice(row.id)}>View</Link>
            </DropdownMenuItem>
            {isPayable(row) ? (
              <>
                <DropdownMenuItem
                  disabled={!write.allowed}
                  onSelect={() => setAction({ kind: 'pay', invoice: row })}
                >
                  Record payment
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => setAction({ kind: 'qr', invoice: row })}>
                  Show QR code
                </DropdownMenuItem>
                <DropdownMenuItem
                  disabled={!write.allowed}
                  onSelect={() => setAction({ kind: 'resend', invoice: row })}
                >
                  Send the bill again
                </DropdownMenuItem>
              </>
            ) : null}
            {row.status !== 'CANCELLED' && row.status !== 'DRAFT' ? (
              isZeroMoney(row.amountPaid) ? (
                <DropdownMenuItem
                  destructive
                  disabled={!write.allowed}
                  onSelect={() => setAction({ kind: 'cancel', invoice: row })}
                >
                  Cancel invoice
                </DropdownMenuItem>
              ) : (
                <DropdownMenuItem disabled>Reverse the payments to cancel</DropdownMenuItem>
              )
            ) : null}
          </>
        )}
        emptyState={
          filtering ? (
            <EmptyState
              title="No invoices match"
              description="Try another status or date range, or clear the filters."
              action={
                <Button
                  variant="secondary"
                  onClick={() => {
                    for (const key of FILTERS) list.setFilter(key, '')
                  }}
                >
                  Clear filters
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No invoices yet"
              description="Generate invoices from a fee plan, or make a one-off invoice for a member."
            />
          )
        }
      />

      <div
        role="status"
        aria-label="Totals"
        className="mt-3 flex flex-wrap items-center justify-between gap-x-6 gap-y-1 rounded-card border border-brand-100 bg-brand-50 px-4 py-3 text-sm"
      >
        {totals.data ? (
          <>
            <span className="font-medium">
              Totals for {totals.data.count.toLocaleString('en-IN')}{' '}
              {totals.data.count === 1 ? 'invoice' : 'invoices'}
              {filtering ? ' that match' : ''}
            </span>
            <span>
              Billed{' '}
              <strong className="tabular-nums">
                {formatMoney(totals.data.amount, { currency })}
              </strong>
            </span>
            <span>
              Paid{' '}
              <strong className="tabular-nums">
                {formatMoney(totals.data.amountPaid, { currency })}
              </strong>
            </span>
            <span>
              Still owed{' '}
              <strong className="tabular-nums">
                {formatMoney(totals.data.balance, { currency })}
              </strong>
            </span>
          </>
        ) : totals.isError ? (
          <span>The totals could not be loaded.</span>
        ) : (
          <span>Adding up</span>
        )}
      </div>

      <InvoiceActionDialogs target={action} onClose={() => setAction(null)} currency={currency} />
    </>
  )
}
