import { Plus } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { formatDate } from '@/lib/dates'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { SimpleSelect } from '@/shared/components/ui/select'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { CommunityListItem } from '@/types'
import {
  CommunityActionDialogs,
  type CommunityActionTarget,
} from '../components/CommunityActionDialogs'
import { CreateCommunityWizard } from '../components/CreateCommunityWizard'
import { useCommunities, useLeadDraft } from '../hooks/useCommunities'
import { useListState } from '@/shared/hooks/useListState'
import { usePlans } from '../hooks/usePlans'

const FILTERS = ['q', 'status', 'plan'] as const
const DEFAULT_SORT = ['createdAt,desc'] as const
const ALL = 'all'
const STATUS_OPTIONS = [
  { value: ALL, label: 'Any status' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'PENDING', label: 'Waiting to start' },
  { value: 'SUSPENDED', label: 'Suspended' },
  { value: 'ARCHIVED', label: 'Archived' },
]

const col = createDataTableColumns<CommunityListItem>()

export default function CommunitiesPage() {
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { q, status, plan } = list.filters
  const [params, setParams] = useSearchParams()
  const plans = usePlans()
  const query = useCommunities({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...(q ? { q } : {}),
    ...(status ? { status } : {}),
    ...(plan ? { plan } : {}),
  })
  const [action, setAction] = useState<CommunityActionTarget | null>(null)

  // ?new=1 opens the wizard; ?lead=<id> opens it pre-filled from that lead.
  const leadId = params.get('lead')
  const draft = useLeadDraft(leadId)
  const wizardOpen = params.get('new') === '1' || (leadId !== null && draft.data !== undefined)
  function setWizardOpen(open: boolean) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        if (open) {
          next.set('new', '1')
        } else {
          next.delete('new')
          next.delete('lead')
        }
        return next
      },
      { replace: true },
    )
  }

  const planOptions = useMemo(
    () => [
      { value: ALL, label: 'Any plan' },
      ...(plans.data ?? []).map((item) => ({ value: item.code, label: item.name })),
    ],
    [plans.data],
  )

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('name', {
          id: 'name',
          header: 'Community',
          cell: (info) => (
            <Link
              to={paths.superadmin.community(info.row.original.id)}
              className="font-medium text-brand-700 underline-offset-2 hover:underline"
            >
              {info.getValue()}
            </Link>
          ),
        }),
        col.accessor('status', {
          id: 'status',
          header: 'Status',
          cell: (info) => <StatusBadge status={info.getValue()} />,
        }),
        col.accessor((row) => row.plan.name, { id: 'plan', header: 'Plan' }),
        col.accessor('memberCount', {
          id: 'memberCount',
          header: 'Members',
          cell: (info) => (
            <span className="tabular-nums">{info.getValue().toLocaleString('en-IN')}</span>
          ),
        }),
        col.accessor('subscriptionEndsOn', {
          id: 'subscriptionEndsOn',
          header: 'Plan ends',
          cell: (info) => {
            const ends = info.getValue()
            return ends ? formatDate(ends) : 'No payment yet'
          },
        }),
        col.accessor('ownerName', {
          header: 'Owner',
          enableSorting: false,
          cell: (info) => info.getValue() ?? '',
        }),
        col.accessor('createdAt', {
          id: 'createdAt',
          header: 'Created',
          cell: (info) => formatDate(info.getValue()),
        }),
      ]),
    [],
  )

  const filtering = q !== '' || status !== '' || plan !== ''

  return (
    <>
      <PageHeader
        title="Communities"
        description="Every community on the platform."
        actions={
          <Button onClick={() => setWizardOpen(true)}>
            <Plus aria-hidden="true" className="size-4" />
            New community
          </Button>
        }
      />

      <DataTable
        label="Communities"
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
          placeholder: 'Search by name or slug',
          label: 'Search communities',
        }}
        filters={
          <>
            <SimpleSelect
              aria-label="Filter by status"
              options={STATUS_OPTIONS}
              value={status || ALL}
              onValueChange={(value) => list.setFilter('status', value === ALL ? '' : value)}
            />
            <SimpleSelect
              aria-label="Filter by plan"
              options={planOptions}
              value={plan || ALL}
              onValueChange={(value) => list.setFilter('plan', value === ALL ? '' : value)}
            />
          </>
        }
        rowLabel={(row) => row.name}
        rowActions={(row) => (
          <>
            <DropdownMenuItem asChild>
              <Link to={paths.superadmin.community(row.id)}>Open</Link>
            </DropdownMenuItem>
            {row.status === 'ACTIVE' ? (
              <DropdownMenuItem
                destructive
                onSelect={() => setAction({ kind: 'suspend', id: row.id, name: row.name })}
              >
                Suspend
              </DropdownMenuItem>
            ) : (
              <DropdownMenuItem
                onSelect={() =>
                  setAction({ kind: 'activate', id: row.id, name: row.name, status: row.status })
                }
              >
                {row.status === 'ARCHIVED' ? 'Restore' : 'Activate'}
              </DropdownMenuItem>
            )}
          </>
        )}
        emptyState={
          filtering ? (
            <EmptyState
              title="No communities match"
              description="Try a different search or clear the filters."
              action={
                <Button
                  variant="secondary"
                  onClick={() => {
                    list.setFilter('q', '')
                    list.setFilter('status', '')
                    list.setFilter('plan', '')
                  }}
                >
                  Clear filters
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No communities yet"
              description="Create the first one, or convert a lead."
              action={<Button onClick={() => setWizardOpen(true)}>New community</Button>}
            />
          )
        }
      />

      <CreateCommunityWizard
        open={wizardOpen}
        onOpenChange={setWizardOpen}
        draft={draft.data}
        leadId={leadId ?? undefined}
      />
      <CommunityActionDialogs target={action} onClose={() => setAction(null)} />
    </>
  )
}
