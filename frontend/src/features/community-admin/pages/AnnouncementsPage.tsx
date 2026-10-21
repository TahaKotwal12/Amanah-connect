import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { formatDateTime } from '@/lib/dates'
import { Button } from '@/shared/components/ui/button'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { useListState } from '@/shared/hooks/useListState'
import { cn } from '@/shared/utils/cn'
import type { AnnouncementStatus, AnnouncementView } from '@/types'
import { AnnouncementComposer } from '../components/AnnouncementComposer'
import { AnnouncementDetailDialog } from '../components/AnnouncementDetailDialog'
import { AUDIENCES } from '../announcementSchemas'
import { useAnnouncements } from '../hooks/useAnnouncements'
import { useWriteAccess } from '../hooks/usePlan'

const FILTERS = ['status'] as const
const DEFAULT_SORT = ['createdAt,desc'] as const
const TABS: ReadonlyArray<{ value: AnnouncementStatus | ''; label: string }> = [
  { value: '', label: 'All' },
  { value: 'DRAFT', label: 'Drafts' },
  { value: 'SCHEDULED', label: 'Scheduled' },
  { value: 'SENT', label: 'Sent' },
]

const col = createDataTableColumns<AnnouncementView>()

function reach(row: AnnouncementView): string {
  if (!row.delivery) return row.sendEmail ? 'Not sent yet' : 'Email off'
  const d = row.delivery
  return `${d.emailsQueued.toLocaleString('en-IN')} emailed of ${d.recipientsTotal.toLocaleString('en-IN')}`
}

export default function AnnouncementsPage() {
  const [params, setParams] = useSearchParams()
  const write = useWriteAccess()
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { status } = list.filters
  const [viewing, setViewing] = useState<AnnouncementView | null>(null)
  const [editing, setEditing] = useState<AnnouncementView | null>(null)
  const composing = params.get('new') === '1' || editing !== null

  const query = useAnnouncements({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...(status ? { status: status as AnnouncementStatus } : {}),
  })

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('title', {
          id: 'title',
          header: 'Announcement',
          cell: (info) => (
            <button
              type="button"
              onClick={() => setViewing(info.row.original)}
              className="text-left font-medium text-brand-700 underline"
            >
              {info.getValue()}
            </button>
          ),
        }),
        col.accessor('audience', {
          id: 'audience',
          header: 'For',
          enableSorting: false,
          cell: (info) => {
            const row = info.row.original
            const label = AUDIENCES.find((a) => a.value === row.audience)?.label ?? 'Members'
            return row.group ? `${label}: ${row.group}` : label
          },
        }),
        col.accessor('status', {
          id: 'status',
          header: 'Status',
          cell: (info) => <StatusBadge status={info.getValue()} />,
        }),
        col.display({
          id: 'when',
          header: 'When',
          cell: (info) => {
            const row = info.row.original
            if (row.sentAt) return formatDateTime(row.sentAt)
            if (row.scheduledAt) return `Goes out ${formatDateTime(row.scheduledAt)}`
            return 'Not sent'
          },
        }),
        col.display({
          id: 'delivery',
          header: 'Reach',
          cell: (info) => reach(info.row.original),
        }),
      ]),
    [],
  )

  function closeComposer() {
    setEditing(null)
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        next.delete('new')
        return next
      },
      { replace: true },
    )
  }

  return (
    <>
      <PageHeader
        title="Announcements"
        description="Write to your members, by email and on record."
        actions={
          <Button
            onClick={() =>
              setParams(
                (previous) => {
                  const next = new URLSearchParams(previous)
                  next.set('new', '1')
                  return next
                },
                { replace: true },
              )
            }
            disabled={!write.allowed}
            {...(write.reason ? { title: write.reason } : {})}
          >
            Write an announcement
          </Button>
        }
      />
      {write.reason ? <p className="-mt-3 mb-4 text-sm text-muted">{write.reason}</p> : null}

      <div role="group" aria-label="Show announcements" className="mb-4 flex flex-wrap gap-2">
        {TABS.map((tab) => (
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
        label="Announcements"
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
        rowLabel={(row) => row.title}
        rowActions={(row) => (
          <>
            <DropdownMenuItem onSelect={() => setViewing(row)}>View</DropdownMenuItem>
            {row.status !== 'SENT' ? (
              <DropdownMenuItem disabled={!write.allowed} onSelect={() => setEditing(row)}>
                Edit and send
              </DropdownMenuItem>
            ) : null}
          </>
        )}
        emptyState={
          <EmptyState
            title={status ? 'Nothing here' : 'No announcements yet'}
            description={
              status
                ? 'Try another tab.'
                : 'Tell your members about a meeting, a change of dates or anything they should know.'
            }
          />
        }
      />

      <AnnouncementComposer
        open={composing}
        onOpenChange={(open) => !open && closeComposer()}
        announcement={editing ?? undefined}
      />
      <AnnouncementDetailDialog announcement={viewing} onClose={() => setViewing(null)} />
    </>
  )
}
