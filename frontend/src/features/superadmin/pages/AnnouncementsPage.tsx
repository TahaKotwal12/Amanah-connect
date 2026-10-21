import { Plus } from 'lucide-react'
import { useMemo, useState } from 'react'
import { formatDateTime } from '@/lib/dates'
import { Button } from '@/shared/components/ui/button'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { SimpleSelect } from '@/shared/components/ui/select'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { PlatformAnnouncementView } from '@/types'
import { AnnouncementDetail } from '../components/AnnouncementDetail'
import { AnnouncementEditor } from '../components/AnnouncementEditor'
import { KIND_OPTIONS } from '../announcementSchema'
import { useAnnouncements } from '../hooks/useAnnouncements'
import { useListState } from '@/shared/hooks/useListState'

const FILTERS = ['status'] as const
const DEFAULT_SORT = ['createdAt,desc'] as const
const ALL = 'all'
const STATUS_OPTIONS = [
  { value: ALL, label: 'Any status' },
  { value: 'DRAFT', label: 'Drafts' },
  { value: 'SCHEDULED', label: 'Scheduled' },
  { value: 'SENT', label: 'Sent' },
]

const col = createDataTableColumns<PlatformAnnouncementView>()
const kindLabel = (kind: string) =>
  KIND_OPTIONS.find((option) => option.value === kind)?.label ?? kind

export default function AnnouncementsPage() {
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { status } = list.filters
  const query = useAnnouncements({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...(status ? { status } : {}),
  })
  const [editing, setEditing] = useState<PlatformAnnouncementView | 'new' | null>(null)
  const [viewing, setViewing] = useState<PlatformAnnouncementView | null>(null)

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('title', {
          id: 'title',
          header: 'Title',
          cell: (info) => (
            <button
              type="button"
              className="text-left font-medium text-brand-700 underline-offset-2 hover:underline"
              onClick={() => setViewing(info.row.original)}
            >
              {info.getValue()}
            </button>
          ),
        }),
        col.accessor('kind', {
          header: 'Kind',
          enableSorting: false,
          cell: (info) => kindLabel(info.getValue()),
        }),
        col.accessor('status', {
          id: 'status',
          header: 'Status',
          cell: (info) => <StatusBadge status={info.getValue()} />,
        }),
        col.accessor(
          (row) =>
            !row.communityIds || row.communityIds.length === 0
              ? 'All communities'
              : `${row.communityIds.length} chosen`,
          {
            id: 'audience',
            header: 'Audience',
            enableSorting: false,
          },
        ),
        col.accessor(
          (row) =>
            [row.sendEmail ? 'Email' : null, row.banner ? 'Banner' : null]
              .filter(Boolean)
              .join(' and '),
          {
            id: 'channels',
            header: 'Channels',
            enableSorting: false,
          },
        ),
        col.accessor((row) => row.sentAt ?? row.scheduledAt, {
          id: 'sentAt',
          header: 'Sent or scheduled',
          enableSorting: false,
          cell: (info) => {
            const when = info.getValue()
            return when ? formatDateTime(when) : ''
          },
        }),
        col.accessor('createdAt', {
          id: 'createdAt',
          header: 'Created',
          cell: (info) => formatDateTime(info.getValue()),
        }),
      ]),
    [],
  )

  return (
    <>
      <PageHeader
        title="Platform announcements"
        description="Messages to community administrators: email, an in-app banner, or both."
        actions={
          <Button onClick={() => setEditing('new')}>
            <Plus aria-hidden="true" className="size-4" />
            New announcement
          </Button>
        }
      />

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
        filters={
          <SimpleSelect
            aria-label="Filter by status"
            options={STATUS_OPTIONS}
            value={status || ALL}
            onValueChange={(value) => list.setFilter('status', value === ALL ? '' : value)}
          />
        }
        rowLabel={(row) => row.title}
        rowActions={(row) => (
          <>
            <DropdownMenuItem onSelect={() => setViewing(row)}>
              {row.status === 'SENT' ? 'View' : 'Preview and send'}
            </DropdownMenuItem>
            {row.status !== 'SENT' ? (
              <DropdownMenuItem onSelect={() => setEditing(row)}>Edit</DropdownMenuItem>
            ) : null}
          </>
        )}
        emptyState={
          <EmptyState
            title={status ? 'No announcements with that status' : 'No announcements yet'}
            description="Write one to reach every community administrator, or just some of them."
            action={<Button onClick={() => setEditing('new')}>New announcement</Button>}
          />
        }
      />

      <AnnouncementEditor
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
        announcement={editing === 'new' || editing === null ? undefined : editing}
      />
      <AnnouncementDetail
        announcement={viewing}
        onClose={() => setViewing(null)}
        onEdit={(announcement) => {
          setViewing(null)
          setEditing(announcement)
        }}
      />
    </>
  )
}
