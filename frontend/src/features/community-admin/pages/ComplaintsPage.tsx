import { LayoutGrid, Table2 } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { formatDate } from '@/lib/dates'
import { Button } from '@/shared/components/ui/button'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { SimpleSelect } from '@/shared/components/ui/select'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { useListState } from '@/shared/hooks/useListState'
import { cn } from '@/shared/utils/cn'
import type { ComplaintStatus, ComplaintView, Priority } from '@/types'
import { ComplaintBoard } from '../components/ComplaintBoard'
import { ComplaintDrawer } from '../components/ComplaintDrawer'
import { PriorityBadge, SlaIndicator } from '../components/ComplaintParts'
import { ComplaintStatusDialog } from '../components/ComplaintStatusDialog'
import { NewComplaintSheet } from '../components/NewComplaintSheet'
import { COMPLAINT_STATUSES, PRIORITIES } from '../complaintSchemas'
import { useComplaintCounts, useComplaints } from '../hooks/useComplaints'
import { useWriteAccess } from '../hooks/usePlan'

const FILTERS = ['status', 'priority', 'q', 'mine', 'unassigned', 'slaBreached'] as const
const DEFAULT_SORT = ['createdAt,desc'] as const
const ANY = 'any'

const col = createDataTableColumns<ComplaintView>()

export default function ComplaintsPage() {
  const [params, setParams] = useSearchParams()
  const write = useWriteAccess()
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { status, priority, q, mine, unassigned, slaBreached } = list.filters
  const counts = useComplaintCounts()
  const [changing, setChanging] = useState<{
    complaint: ComplaintView
    to: ComplaintStatus
  } | null>(null)

  const view = params.get('view') === 'board' ? 'board' : 'table'
  const openId = params.get('open')
  const adding = params.get('new') === '1'

  function update(change: (next: URLSearchParams) => void) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        change(next)
        return next
      },
      { replace: true },
    )
  }

  const filters = {
    ...(priority ? { priority: priority as Priority } : {}),
    ...(q ? { q } : {}),
    ...(mine === '1' ? { mine: true } : {}),
    ...(unassigned === '1' ? { unassigned: true } : {}),
    ...(slaBreached === '1' ? { slaBreached: true } : {}),
  }
  const query = useComplaints({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...(status ? { status: status as ComplaintStatus } : {}),
    ...filters,
  })

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('subject', {
          id: 'subject',
          header: 'Complaint',
          cell: (info) => (
            <button
              type="button"
              onClick={() => update((n) => n.set('open', info.row.original.id))}
              className="text-left font-medium text-brand-700 underline"
            >
              {info.getValue()}
            </button>
          ),
        }),
        col.accessor('memberName', {
          id: 'member',
          header: 'Member',
          enableSorting: false,
          cell: (info) => info.getValue() ?? 'None',
        }),
        col.accessor('priority', {
          id: 'priority',
          header: 'Priority',
          cell: (info) => <PriorityBadge priority={info.getValue()} />,
        }),
        col.accessor('status', {
          id: 'status',
          header: 'Status',
          cell: (info) => <StatusBadge status={info.getValue()} />,
        }),
        col.display({
          id: 'sla',
          header: 'Age',
          cell: (info) => <SlaIndicator complaint={info.row.original} />,
        }),
        col.accessor('assignedToName', {
          id: 'assignee',
          header: 'Assigned to',
          enableSorting: false,
          cell: (info) => info.getValue() ?? 'Nobody',
        }),
        col.accessor('createdAt', {
          id: 'createdAt',
          header: 'Logged',
          cell: (info) => formatDate(info.getValue().slice(0, 10)),
        }),
      ]),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  const filtering =
    status !== '' ||
    priority !== '' ||
    q !== '' ||
    mine !== '' ||
    unassigned !== '' ||
    slaBreached !== ''
  const c = counts.data

  function toggle(key: 'mine' | 'unassigned' | 'slaBreached') {
    list.setFilter(key, list.filters[key] === '1' ? '' : '1')
  }

  return (
    <>
      <PageHeader
        title="Complaints"
        description="Log, work through and resolve complaints."
        actions={
          <Button
            onClick={() => update((n) => n.set('new', '1'))}
            disabled={!write.allowed}
            {...(write.reason ? { title: write.reason } : {})}
          >
            Log a complaint
          </Button>
        }
      />
      {write.reason ? <p className="-mt-3 mb-4 text-sm text-muted">{write.reason}</p> : null}

      <section aria-label="Summary" className="mb-4 flex flex-wrap gap-x-6 gap-y-1 text-sm">
        <span>
          <strong>{c?.active ?? '…'}</strong> active
        </span>
        <span>
          <strong>{c?.slaBreached ?? '…'}</strong> past their target
        </span>
        <span>
          <strong>{c?.urgentActive ?? '…'}</strong> urgent
        </span>
        <span>
          <strong>{c?.unassignedActive ?? '…'}</strong> unassigned
        </span>
      </section>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div role="group" aria-label="View" className="flex gap-1">
          {(
            [
              ['table', 'Table', Table2],
              ['board', 'Board', LayoutGrid],
            ] as const
          ).map(([id, label, Icon]) => (
            <button
              key={id}
              type="button"
              aria-pressed={view === id}
              onClick={() =>
                update((n) => (id === 'board' ? n.set('view', 'board') : n.delete('view')))
              }
              className={cn(
                'inline-flex min-h-11 items-center gap-2 rounded border px-3 text-sm font-medium',
                view === id
                  ? 'border-brand-600 bg-brand-600 text-white'
                  : 'border-brand-200 bg-white text-brand-900 hover:border-brand-500',
              )}
            >
              <Icon aria-hidden="true" className="size-4" />
              {label}
            </button>
          ))}
        </div>
        <SimpleSelect
          aria-label="Priority"
          options={[{ value: ANY, label: 'Any priority' }, ...PRIORITIES]}
          value={priority || ANY}
          onValueChange={(value) => list.setFilter('priority', value === ANY ? '' : value)}
          className="w-44"
        />
        {(
          [
            ['mine', 'Assigned to me'],
            ['unassigned', 'Unassigned'],
            ['slaBreached', 'Past their target'],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            type="button"
            aria-pressed={list.filters[key] === '1'}
            onClick={() => toggle(key)}
            className={cn(
              'inline-flex min-h-11 items-center rounded border px-3 text-sm font-medium',
              list.filters[key] === '1'
                ? 'border-brand-600 bg-brand-600 text-white'
                : 'border-brand-200 bg-white text-brand-900 hover:border-brand-500',
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {view === 'board' ? (
        <ComplaintBoard
          filters={filters}
          onOpen={(id) => update((n) => n.set('open', id))}
          onChangeStatus={(complaint, to) => setChanging({ complaint, to })}
        />
      ) : (
        <>
          <div role="group" aria-label="Show complaints" className="mb-4 flex flex-wrap gap-2">
            {[{ value: '', label: 'All' }, ...COMPLAINT_STATUSES].map((tab) => (
              <button
                key={tab.label}
                type="button"
                aria-pressed={tab.value === status}
                onClick={() => list.setFilter('status', tab.value)}
                className={cn(
                  'inline-flex min-h-11 items-center rounded border px-3 text-sm font-medium',
                  tab.value === status
                    ? 'border-brand-600 bg-brand-600 text-white'
                    : 'border-brand-200 bg-white text-brand-900 hover:border-brand-500',
                )}
              >
                {tab.label}
              </button>
            ))}
          </div>
          <DataTable
            label="Complaints"
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
              placeholder: 'Search subject, description or member',
              label: 'Search complaints',
            }}
            rowLabel={(row) => row.subject}
            rowActions={(row) => (
              <>
                <DropdownMenuItem onSelect={() => update((n) => n.set('open', row.id))}>
                  Open
                </DropdownMenuItem>
                <DropdownMenuItem
                  disabled={!write.allowed}
                  onSelect={() => setChanging({ complaint: row, to: row.status })}
                >
                  Change status
                </DropdownMenuItem>
              </>
            )}
            emptyState={
              filtering ? (
                <EmptyState
                  title="No complaints match"
                  description="Try another status or priority, or clear the filters."
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
                  title="No complaints yet"
                  description="When a member raises a problem, log it here so it is tracked to a resolution."
                />
              )
            }
          />
        </>
      )}

      <ComplaintDrawer id={openId} onClose={() => update((n) => n.delete('open'))} />
      <ComplaintStatusDialog
        complaint={changing?.complaint ?? null}
        initial={changing?.to}
        onClose={() => setChanging(null)}
      />
      <NewComplaintSheet
        open={adding}
        onOpenChange={(open) => !open && update((n) => n.delete('new'))}
        onCreated={(id) =>
          update((n) => {
            n.delete('new')
            n.set('open', id)
          })
        }
      />
    </>
  )
}
