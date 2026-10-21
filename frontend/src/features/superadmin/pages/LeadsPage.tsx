import { LayoutList, Columns3 } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useSearchParams } from 'react-router'
import { formatDateTime, formatRelative } from '@/lib/dates'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { SimpleSelect } from '@/shared/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { LeadStatus, LeadView } from '@/types'
import { notify } from '@/lib/notify'
import { useChangeLeadStatus, useLead, useLeads } from '../hooks/useLeads'
import { useListState } from '@/shared/hooks/useListState'

const FILTERS = ['q', 'status'] as const
const DEFAULT_SORT = ['createdAt,desc'] as const
const ALL = 'all'
const PIPELINE: ReadonlyArray<{ status: LeadStatus; label: string }> = [
  { status: 'NEW', label: 'New' },
  { status: 'CONTACTED', label: 'Contacted' },
  { status: 'DEMO_SCHEDULED', label: 'Demo scheduled' },
  { status: 'CONVERTED', label: 'Converted' },
  { status: 'LOST', label: 'Lost' },
]
const STATUS_OPTIONS = [{ value: ALL, label: 'Any status' }].concat(
  PIPELINE.map(({ status, label }) => ({ value: status, label })),
)
/** CONVERTED is only ever set by creating the community from the lead. */
const SETTABLE = PIPELINE.filter(({ status }) => status !== 'CONVERTED').map(
  ({ status, label }) => ({
    value: status,
    label,
  }),
)

const BOARD_PAGE_SIZE = 50

const col = createDataTableColumns<LeadView>()

// ---- one board column ---------------------------------------------------------------------------

function BoardColumn({
  status,
  label,
  onOpen,
}: {
  status: LeadStatus
  label: string
  onOpen: (id: string) => void
}) {
  const query = useLeads({ status, size: BOARD_PAGE_SIZE, sort: ['createdAt,desc'] })
  return (
    <section
      aria-label={`${label} leads`}
      className="flex min-h-48 w-72 shrink-0 flex-col gap-3 rounded-card border border-brand-100 bg-brand-50 p-3"
    >
      <h2 className="flex items-center justify-between font-heading text-base font-semibold text-brand-900">
        {label}
        <span className="rounded bg-white px-2 py-0.5 text-sm font-medium tabular-nums text-muted">
          {query.data?.total ?? 0}
        </span>
      </h2>
      {query.isError ? (
        <ErrorState
          headingLevel={3}
          error={query.error}
          onRetry={() => void query.refetch()}
          retrying={query.isFetching}
        />
      ) : query.isLoading ? (
        <Skeleton className="h-24" role="status" aria-label={`Loading ${label} leads`} />
      ) : query.data && query.data.items.length > 0 ? (
        <ul className="grid gap-2">
          {query.data.items.map((lead) => (
            <li key={lead.id}>
              <button
                type="button"
                onClick={() => onOpen(lead.id)}
                className="block min-h-11 w-full rounded border border-brand-100 bg-white p-3 text-left hover:border-brand-500"
              >
                <span className="block font-medium text-brand-900">{lead.name}</span>
                <span className="block truncate text-sm text-muted">
                  {lead.communityName ?? lead.email}
                </span>
                <span className="block text-xs text-muted">{formatRelative(lead.createdAt)}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted">No leads here.</p>
      )}
    </section>
  )
}

// ---- the detail panel -----------------------------------------------------------------------------

function LeadSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const lead = useLead(id)
  const change = useChangeLeadStatus()
  const data = lead.data

  async function setStatus(status: string) {
    if (!data || status === data.status) return
    try {
      await change.mutateAsync({ id: data.id, status: status as LeadStatus })
    } catch (error) {
      notify.apiError(error)
    }
  }

  return (
    <Sheet open={id !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>{data?.name ?? 'Lead'}</SheetTitle>
          <SheetDescription>
            {data ? `Asked for a demo ${formatRelative(data.createdAt)}` : 'Loading'}
          </SheetDescription>
        </SheetHeader>
        {lead.isError ? (
          <ErrorState
            headingLevel={3}
            error={lead.error}
            onRetry={() => void lead.refetch()}
            retrying={lead.isFetching}
          />
        ) : !data ? (
          <Skeleton className="h-48" role="status" aria-label="Loading lead" />
        ) : (
          <div className="grid gap-5">
            <dl className="grid gap-3 text-sm">
              <div>
                <dt className="text-muted">Email</dt>
                <dd className="font-medium">
                  <a className="text-brand-700 underline" href={`mailto:${data.email}`}>
                    {data.email}
                  </a>
                </dd>
              </div>
              {data.phone ? (
                <div>
                  <dt className="text-muted">Phone</dt>
                  <dd className="font-medium">{data.phone}</dd>
                </div>
              ) : null}
              <div>
                <dt className="text-muted">Community</dt>
                <dd className="font-medium">
                  {data.communityName ?? 'Not given'}
                  {data.size ? `, about ${data.size.toLocaleString('en-IN')} members` : ''}
                </dd>
              </div>
              {data.message ? (
                <div>
                  <dt className="text-muted">Message</dt>
                  <dd className="whitespace-pre-wrap">{data.message}</dd>
                </div>
              ) : null}
              <div>
                <dt className="text-muted">Received</dt>
                <dd>{formatDateTime(data.createdAt)}</dd>
              </div>
            </dl>

            <div className="grid gap-1.5">
              <label htmlFor="lead-status" className="text-sm font-medium">
                Status
              </label>
              {data.status === 'CONVERTED' ? (
                <p className="flex items-center gap-3 text-sm">
                  <StatusBadge status="converted" />
                  {data.convertedCommunityId ? (
                    <Link
                      className="text-brand-700 underline"
                      to={paths.superadmin.community(data.convertedCommunityId)}
                    >
                      Open the community
                    </Link>
                  ) : null}
                </p>
              ) : (
                <SimpleSelect
                  id="lead-status"
                  options={SETTABLE}
                  value={data.status}
                  onValueChange={(value) => void setStatus(value)}
                />
              )}
            </div>

            {data.status !== 'CONVERTED' ? (
              <Button asChild>
                <Link to={`${paths.superadmin.communities}?lead=${data.id}`}>
                  Convert to a community
                </Link>
              </Button>
            ) : null}
          </div>
        )}
      </SheetContent>
    </Sheet>
  )
}

// ---- the page ------------------------------------------------------------------------------------

export default function LeadsPage() {
  const [params, setParams] = useSearchParams()
  const view = params.get('view') === 'table' ? 'table' : 'board'
  const openId = params.get('lead')
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { q, status } = list.filters
  const query = useLeads({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...(q ? { q } : {}),
    ...(status ? { status: status as LeadStatus } : {}),
  })

  function setParam(key: string, value: string | null) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        if (value) next.set(key, value)
        else next.delete(key)
        return next
      },
      { replace: true },
    )
  }

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          id: 'name',
          header: 'Name',
          cell: (info) => (
            <button
              type="button"
              className="font-medium text-brand-700 underline-offset-2 hover:underline"
              onClick={() => setParam('lead', info.row.original.id)}
            >
              {info.getValue()}
            </button>
          ),
        }),
        col.accessor('communityName', {
          id: 'communityName',
          header: 'Community',
          cell: (info) => info.getValue() ?? '',
        }),
        col.accessor('email', { header: 'Email', enableSorting: false }),
        col.accessor('status', {
          id: 'status',
          header: 'Status',
          cell: (info) => <StatusBadge status={info.getValue()} />,
        }),
        col.accessor('createdAt', {
          id: 'createdAt',
          header: 'Received',
          cell: (info) => formatDateTime(info.getValue()),
        }),
      ]),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- setParam only writes the address
    [],
  )

  const filtering = q !== '' || status !== ''

  return (
    <>
      <PageHeader
        title="Leads"
        description="Requests for a demo from the website."
        actions={
          <div role="group" aria-label="View" className="flex gap-2">
            <Button
              variant={view === 'board' ? 'primary' : 'secondary'}
              aria-pressed={view === 'board'}
              onClick={() => setParam('view', null)}
            >
              <Columns3 aria-hidden="true" className="size-4" />
              Board
            </Button>
            <Button
              variant={view === 'table' ? 'primary' : 'secondary'}
              aria-pressed={view === 'table'}
              onClick={() => setParam('view', 'table')}
            >
              <LayoutList aria-hidden="true" className="size-4" />
              Table
            </Button>
          </div>
        }
      />

      {view === 'board' ? (
        <div className="flex gap-4 overflow-x-auto pb-4">
          {PIPELINE.map(({ status: columnStatus, label }) => (
            <BoardColumn
              key={columnStatus}
              status={columnStatus}
              label={label}
              onOpen={(id) => setParam('lead', id)}
            />
          ))}
        </div>
      ) : (
        <DataTable
          label="Leads"
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
            placeholder: 'Search by name, email or community',
            label: 'Search leads',
          }}
          filters={
            <SimpleSelect
              aria-label="Filter by status"
              options={STATUS_OPTIONS}
              value={status || ALL}
              onValueChange={(value) => list.setFilter('status', value === ALL ? '' : value)}
            />
          }
          emptyState={
            filtering ? (
              <EmptyState title="No leads match" description="Try a different search or status." />
            ) : (
              <EmptyState
                title="No leads yet"
                description="Requests from the website demo form appear here."
              />
            )
          }
        />
      )}

      <LeadSheet id={openId} onClose={() => setParam('lead', null)} />
    </>
  )
}
