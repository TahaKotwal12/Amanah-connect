import { Download, Plus, Upload } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { formatDate } from '@/lib/dates'
import { downloadBlob } from '@/lib/download'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { ReasonDialog } from '@/shared/components/ui/reason-dialog'
import { SearchInput } from '@/shared/components/ui/search-input'
import { SimpleSelect } from '@/shared/components/ui/select'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { useListState } from '@/shared/hooks/useListState'
import type { MemberStatus, MemberView } from '@/types'
import { exportMembers } from '../api/members'
import { MemberImportWizard } from '../components/MemberImportWizard'
import { MemberSheet } from '../components/MemberSheet'
import { PlanMeter } from '../components/PlanMeter'
import { useActivateMember, useDeactivateMember, useMembers } from '../hooks/useMembers'
import { useWriteAccess } from '../hooks/usePlan'
import { useSettings } from '../hooks/useSettings'

const FILTERS = ['q', 'status', 'group'] as const
const DEFAULT_SORT = ['name,asc'] as const
const ALL = 'all'
const STATUS_OPTIONS = [
  { value: ALL, label: 'Any status' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
]

const col = createDataTableColumns<MemberView>()

export default function MembersPage() {
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { q, status, group } = list.filters
  const [params, setParams] = useSearchParams()
  const write = useWriteAccess()
  const settings = useSettings()
  const groupLabel = settings.data?.memberGroupLabel ?? 'Group'
  const filters = {
    ...(q ? { q } : {}),
    ...(status ? { status: status as MemberStatus } : {}),
    ...(group ? { group } : {}),
  }
  const query = useMembers({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...filters,
  })
  const deactivate = useDeactivateMember()
  const activate = useActivateMember()
  const [editing, setEditing] = useState<MemberView | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [deactivating, setDeactivating] = useState<MemberView | null>(null)
  const [activating, setActivating] = useState<MemberView | null>(null)
  const [exporting, setExporting] = useState(false)

  // ?new=1 opens the add panel (the dashboard's "Add member" goes here).
  const adding = params.get('new') === '1'
  function setAdding(open: boolean) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        if (open) next.set('new', '1')
        else next.delete('new')
        return next
      },
      { replace: true },
    )
  }

  async function download() {
    setExporting(true)
    try {
      downloadBlob('members.csv', await exportMembers(filters))
    } catch (error) {
      notify.apiError(error)
    } finally {
      setExporting(false)
    }
  }

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('memberNo', { id: 'memberNo', header: 'No.' }),
        col.accessor('fullName', {
          id: 'name',
          header: 'Name',
          cell: (info) => (
            <Link
              to={paths.admin.member(info.row.original.id)}
              className="font-medium text-brand-700 underline-offset-2 hover:underline"
            >
              {info.getValue()}
            </Link>
          ),
        }),
        col.accessor('group', {
          id: 'group',
          header: groupLabel,
          cell: (info) => info.getValue() ?? '',
        }),
        col.accessor('email', {
          header: 'Email',
          enableSorting: false,
          cell: (info) => info.getValue() ?? '',
        }),
        col.accessor('phone', {
          header: 'Phone',
          enableSorting: false,
          cell: (info) => info.getValue() ?? '',
        }),
        col.accessor('status', {
          id: 'status',
          header: 'Status',
          cell: (info) => <StatusBadge status={info.getValue()} />,
        }),
        col.accessor('joinedOn', {
          id: 'joinedOn',
          header: 'Joined',
          cell: (info) => {
            const joined = info.getValue()
            return joined ? formatDate(joined) : ''
          },
        }),
      ]),
    [groupLabel],
  )

  const filtering = q !== '' || status !== '' || group !== ''

  return (
    <>
      <PageHeader
        title="Members"
        description="Everyone in the community. Members do not sign in: they hear from you by email."
        actions={
          <>
            <Button
              onClick={() => setAdding(true)}
              disabled={!write.allowed}
              {...(write.reason ? { title: write.reason } : {})}
            >
              <Plus aria-hidden="true" className="size-4" />
              Add member
            </Button>
            <Button
              variant="secondary"
              onClick={() => setImportOpen(true)}
              disabled={!write.allowed}
              {...(write.reason ? { title: write.reason } : {})}
            >
              <Upload aria-hidden="true" className="size-4" />
              Import CSV
            </Button>
            <Button variant="secondary" onClick={() => void download()} loading={exporting}>
              <Download aria-hidden="true" className="size-4" />
              Export CSV
            </Button>
          </>
        }
      />
      {write.reason ? <p className="-mt-3 mb-4 text-sm text-muted">{write.reason}</p> : null}

      <PlanMeter />

      <DataTable
        label="Members"
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
          placeholder: 'Search name, number, email or phone',
          label: 'Search members',
        }}
        filters={
          <>
            <SimpleSelect
              aria-label="Filter by status"
              options={STATUS_OPTIONS}
              value={status || ALL}
              onValueChange={(value) => list.setFilter('status', value === ALL ? '' : value)}
            />
            <SearchInput
              value={group}
              onSearch={(value) => list.setFilter('group', value)}
              label={`Filter by ${groupLabel.toLowerCase()}`}
              placeholder={groupLabel}
              className="w-44"
            />
          </>
        }
        rowLabel={(row) => row.fullName}
        rowActions={(row) => (
          <>
            <DropdownMenuItem asChild>
              <Link to={paths.admin.member(row.id)}>Open</Link>
            </DropdownMenuItem>
            <DropdownMenuItem disabled={!write.allowed} onSelect={() => setEditing(row)}>
              Edit
            </DropdownMenuItem>
            {row.status === 'ACTIVE' ? (
              <DropdownMenuItem
                destructive
                disabled={!write.allowed}
                onSelect={() => setDeactivating(row)}
              >
                Deactivate
              </DropdownMenuItem>
            ) : (
              <DropdownMenuItem disabled={!write.allowed} onSelect={() => setActivating(row)}>
                Activate
              </DropdownMenuItem>
            )}
          </>
        )}
        emptyState={
          filtering ? (
            <EmptyState
              title="No members match"
              description="Try a different search or clear the filters."
              action={
                <Button
                  variant="secondary"
                  onClick={() => {
                    list.setFilter('q', '')
                    list.setFilter('status', '')
                    list.setFilter('group', '')
                  }}
                >
                  Clear filters
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No members yet"
              description="Add the first member, import a list from a file, or invite people to register."
              action={
                write.allowed ? (
                  <Button onClick={() => setAdding(true)}>Add member</Button>
                ) : undefined
              }
            />
          )
        }
      />

      <MemberSheet open={adding} onOpenChange={setAdding} />
      <MemberSheet
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
        member={editing ?? undefined}
      />
      <MemberImportWizard open={importOpen} onOpenChange={setImportOpen} />

      <ReasonDialog
        open={deactivating !== null}
        onOpenChange={(open) => !open && setDeactivating(null)}
        title={`Deactivate ${deactivating?.fullName ?? 'this member'}?`}
        description="They stop receiving bills and reminders. Their history stays, and you can activate them again."
        confirmLabel="Deactivate"
        onSubmit={async (reason) => {
          if (!deactivating) return
          await deactivate.mutateAsync({ id: deactivating.id, reason })
          notify.success(`${deactivating.fullName} is inactive`)
        }}
      />
      <ReasonDialog
        open={activating !== null}
        onOpenChange={(open) => !open && setActivating(null)}
        title={`Activate ${activating?.fullName ?? 'this member'}?`}
        description="They get bills and reminders again."
        confirmLabel="Activate"
        variant="default"
        required={false}
        onSubmit={async (reason) => {
          if (!activating) return
          await activate.mutateAsync({ id: activating.id, body: reason ? { reason } : {} })
          notify.success(`${activating.fullName} is active`)
        }}
      />
    </>
  )
}
