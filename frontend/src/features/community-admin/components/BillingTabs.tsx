import { Plus } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { formatDate } from '@/lib/dates'
import { downloadBlob } from '@/lib/download'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Badge } from '@/shared/components/ui/badge'
import { Button } from '@/shared/components/ui/button'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { useListState } from '@/shared/hooks/useListState'
import type { DefaulterView, FeePlanView, ReceiptView } from '@/types'
import { fetchReceiptPdf } from '../api/billing'
import { FEE_AUDIENCES, FEE_FREQUENCIES, FEE_KINDS } from '../billingSchemas'
import {
  useDefaulters,
  useFeePlans,
  useReceipts,
  useRemindOverdue,
  useResendReceipt,
} from '../hooks/useBilling'
import { useWriteAccess } from '../hooks/usePlan'
import { FeePlanSheet } from './FeePlanSheet'

const label = (options: ReadonlyArray<{ value: string; label: string }>, value: string) =>
  options.find((option) => option.value === value)?.label ?? value

// ---- fee plans --------------------------------------------------------------------------------

export function FeePlansTab({
  currency = 'INR',
  onGenerate,
}: {
  currency?: string
  onGenerate: () => void
}) {
  const plans = useFeePlans()
  const write = useWriteAccess()
  const [editing, setEditing] = useState<FeePlanView | 'new' | null>(null)

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-prose text-sm text-muted">
          A fee plan says what members are charged and how often. Billing a plan creates one invoice
          per member for the period.
        </p>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={onGenerate} disabled={!write.allowed}>
            Generate invoices
          </Button>
          <Button onClick={() => setEditing('new')} disabled={!write.allowed}>
            <Plus aria-hidden="true" className="size-4" />
            New fee plan
          </Button>
        </div>
      </div>

      {plans.isError ? (
        <ErrorState
          headingLevel={3}
          error={plans.error}
          onRetry={() => void plans.refetch()}
          retrying={plans.isFetching}
        />
      ) : plans.isLoading ? (
        <Skeleton className="h-48" role="status" aria-label="Loading fee plans" />
      ) : plans.data && plans.data.length > 0 ? (
        <ul aria-label="Fee plans" className="grid gap-3 md:grid-cols-2">
          {plans.data.map((plan) => (
            <li
              key={plan.id}
              className="grid gap-2 rounded-card border border-brand-100 bg-white p-4"
            >
              <div className="flex flex-wrap items-start justify-between gap-2">
                <h3 className="font-heading text-lg font-semibold text-brand-900">{plan.name}</h3>
                <div className="flex gap-1.5">
                  <Badge variant={plan.active ? 'success' : 'neutral'}>
                    {plan.active ? 'In use' : 'Retired'}
                  </Badge>
                  {plan.autoGenerate ? <Badge variant="brand">Automatic</Badge> : null}
                </div>
              </div>
              <p className="font-heading text-2xl font-semibold lining-nums tabular-nums text-brand-900">
                {formatMoney(plan.amount, { currency })}
              </p>
              <p className="text-sm text-muted">
                {label(FEE_KINDS, plan.kind)},{' '}
                {label(FEE_FREQUENCIES, plan.frequency).toLowerCase()}
                {plan.dueDay ? `, due on day ${plan.dueDay}` : ''}
              </p>
              <p className="text-sm">
                {label(FEE_AUDIENCES, plan.appliesTo)}
                {plan.appliesTo === 'GROUP' && plan.group ? `: ${plan.group}` : ''}
                {plan.appliesTo === 'SELECTED' ? `: ${plan.memberIds.length}` : ''}
              </p>
              {plan.lastGeneratedPeriod ? (
                <p className="text-sm text-muted">Last billed for {plan.lastGeneratedPeriod}</p>
              ) : null}
              <div>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={!write.allowed}
                  onClick={() => setEditing(plan)}
                  aria-label={`Edit ${plan.name}`}
                >
                  Edit
                </Button>
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <EmptyState
          title="No fee plans yet"
          description="Create one, for example monthly maintenance, and bill it to every member in one go."
          action={
            write.allowed ? (
              <Button onClick={() => setEditing('new')}>New fee plan</Button>
            ) : undefined
          }
        />
      )}

      <FeePlanSheet
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
        plan={editing === 'new' || editing === null ? undefined : editing}
      />
    </>
  )
}

// ---- receipts ---------------------------------------------------------------------------------

const receiptCol = createDataTableColumns<ReceiptView>()

export function ReceiptsTab({ currency = 'INR' }: { currency?: string }) {
  const list = useListState(['q', 'from', 'to'] as const, ['createdAt,desc'])
  const { q, from, to } = list.filters
  const query = useReceipts({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    ...(q ? { q } : {}),
    ...(from ? { from } : {}),
    ...(to ? { to } : {}),
  })
  const resend = useResendReceipt()
  const write = useWriteAccess()

  async function download(receipt: ReceiptView) {
    try {
      downloadBlob(`${receipt.receiptNo}.pdf`, await fetchReceiptPdf(receipt.id))
    } catch (error) {
      notify.apiError(error)
    }
  }

  async function emailAgain(receipt: ReceiptView) {
    try {
      await resend.mutateAsync(receipt.id)
      notify.success(`${receipt.receiptNo} was emailed again`)
    } catch (error) {
      notify.apiError(error)
    }
  }

  const columns = useMemo(
    () =>
      receiptCol.columns([
        receiptCol.accessor('receiptNo', { header: 'Receipt', enableSorting: false }),
        receiptCol.accessor('payerName', {
          header: 'From',
          enableSorting: false,
          cell: (info) => info.getValue() ?? '',
        }),
        receiptCol.accessor('invoiceNo', {
          header: 'Invoice',
          enableSorting: false,
          cell: (info) => {
            const row = info.row.original
            return row.invoiceId ? (
              <Link
                to={paths.admin.invoice(row.invoiceId)}
                className="text-brand-700 underline-offset-2 hover:underline"
              >
                {info.getValue()}
              </Link>
            ) : (
              'Donation'
            )
          },
        }),
        receiptCol.accessor('receivedOn', {
          header: 'Received',
          enableSorting: false,
          cell: (info) => formatDate(info.getValue()),
        }),
        receiptCol.accessor('method', { header: 'Method', enableSorting: false }),
        receiptCol.accessor('amount', {
          header: 'Amount',
          enableSorting: false,
          cell: (info) => (
            <span className="tabular-nums">{formatMoney(info.getValue(), { currency })}</span>
          ),
        }),
        receiptCol.accessor('reversed', {
          header: 'State',
          enableSorting: false,
          cell: (info) =>
            info.getValue() ? (
              <StatusBadge status="reversed" />
            ) : (
              <StatusBadge status="paid" label="Valid" />
            ),
        }),
      ]),
    [currency],
  )

  const filtering = q !== '' || from !== '' || to !== ''

  return (
    <DataTable
      label="Receipts"
      columns={columns}
      data={query.data?.items ?? []}
      getRowId={(row) => row.id}
      rowCount={query.data?.total ?? 0}
      pagination={list.pagination}
      onPaginationChange={list.setPagination}
      isLoading={query.isLoading}
      isFetching={query.isFetching}
      error={query.error}
      onRetry={() => void query.refetch()}
      search={{
        value: q,
        onSearch: (value) => list.setFilter('q', value),
        placeholder: 'Search receipt, name or invoice',
        label: 'Search receipts',
      }}
      filters={
        <>
          <div className="flex items-center gap-2">
            <label htmlFor="rec-from" className="text-sm text-muted">
              From
            </label>
            <DatePicker
              id="rec-from"
              value={from || undefined}
              onValueChange={(value) => list.setFilter('from', value ?? '')}
              {...(to ? { max: to } : {})}
              className="w-44"
            />
          </div>
          <div className="flex items-center gap-2">
            <label htmlFor="rec-to" className="text-sm text-muted">
              to
            </label>
            <DatePicker
              id="rec-to"
              value={to || undefined}
              onValueChange={(value) => list.setFilter('to', value ?? '')}
              {...(from ? { min: from } : {})}
              className="w-44"
            />
          </div>
        </>
      }
      rowLabel={(row) => row.receiptNo}
      rowActions={(row) => (
        <>
          <DropdownMenuItem onSelect={() => void download(row)}>Download PDF</DropdownMenuItem>
          <DropdownMenuItem
            disabled={!write.allowed || row.reversed}
            onSelect={() => void emailAgain(row)}
          >
            {row.reversed ? 'Cannot email a reversed receipt' : 'Email again'}
          </DropdownMenuItem>
        </>
      )}
      emptyState={
        <EmptyState
          title={filtering ? 'No receipts match' : 'No receipts yet'}
          description="A receipt is made each time you record a payment."
        />
      }
    />
  )
}

// ---- defaulters -------------------------------------------------------------------------------

const defaulterCol = createDataTableColumns<DefaulterView>()

export function DefaultersTab({ currency = 'INR' }: { currency?: string }) {
  const list = useListState([] as const, [])
  const query = useDefaulters({ page: list.pagination.pageIndex, size: list.pagination.pageSize })
  const remind = useRemindOverdue()
  const write = useWriteAccess()
  const [confirming, setConfirming] = useState(false)
  const total = query.data?.total ?? 0

  const columns = useMemo(
    () =>
      defaulterCol.columns([
        defaulterCol.accessor('fullName', {
          header: 'Member',
          enableSorting: false,
          cell: (info) => (
            <Link
              to={paths.admin.member(info.row.original.memberId)}
              className="font-medium text-brand-700 underline-offset-2 hover:underline"
            >
              {info.getValue()}
            </Link>
          ),
        }),
        defaulterCol.accessor('memberNo', { header: 'No.', enableSorting: false }),
        defaulterCol.accessor('overdueInvoices', {
          header: 'Overdue invoices',
          enableSorting: false,
        }),
        defaulterCol.accessor('overdueAmount', {
          header: 'Owes',
          enableSorting: false,
          cell: (info) => (
            <span className="tabular-nums">{formatMoney(info.getValue(), { currency })}</span>
          ),
        }),
        defaulterCol.accessor('oldestDueDate', {
          header: 'Oldest due',
          enableSorting: false,
          cell: (info) => formatDate(info.getValue()),
        }),
        defaulterCol.accessor('lastRemindedOn', {
          header: 'Last reminded',
          enableSorting: false,
          cell: (info) => {
            const day = info.getValue()
            return day ? formatDate(day) : 'Not yet'
          },
        }),
      ]),
    [currency],
  )

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <p className="max-w-prose text-sm text-muted">
          Members with invoices past their due date, who owe the most first.
        </p>
        <Button onClick={() => setConfirming(true)} disabled={total === 0 || !write.allowed}>
          Send a reminder to all overdue
        </Button>
      </div>
      <DataTable
        label="Defaulters"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(row) => row.memberId}
        rowCount={total}
        pagination={list.pagination}
        onPaginationChange={list.setPagination}
        isLoading={query.isLoading}
        isFetching={query.isFetching}
        error={query.error}
        onRetry={() => void query.refetch()}
        emptyState={
          <EmptyState
            title="Nobody is overdue"
            description="Members who fall behind appear here."
          />
        }
      />
      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={`Remind ${total.toLocaleString('en-IN')} ${total === 1 ? 'member' : 'members'}?`}
        description={`An overdue notice is emailed for every overdue invoice of these ${total.toLocaleString('en-IN')} ${total === 1 ? 'member' : 'members'}. Members with no email address, or who have not agreed to emails, are skipped. Each invoice is reminded once a day at most.`}
        confirmLabel="Send reminders"
        onConfirm={async () => {
          try {
            const result = await remind.mutateAsync()
            const parts = [`${result.queued.toLocaleString('en-IN')} sent`]
            if (result.alreadyRemindedToday > 0)
              parts.push(`${result.alreadyRemindedToday} already reminded today`)
            if (result.skipped > 0) parts.push(`${result.skipped} could not be emailed`)
            notify.success(parts.join(', '))
          } catch (error) {
            notify.apiError(error)
            throw error
          }
        }}
      />
    </>
  )
}
