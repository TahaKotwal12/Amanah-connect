import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { formatDate, formatDateTime } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { SimpleSelect } from '@/shared/components/ui/select'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { InviteRow } from '@/types'
import { useListState } from '@/shared/hooks/useListState'
import { useInvites, useResendInvite, useRevokeInvite } from '../hooks/useInvites'

const FILTERS = ['q', 'status'] as const
const DEFAULT_SORT = ['invitedAt,desc'] as const
const ALL = 'all'
const STATUS_OPTIONS = [
  { value: ALL, label: 'Any status' },
  { value: 'PENDING', label: 'Waiting' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'EXPIRED', label: 'Expired' },
  { value: 'REVOKED', label: 'Revoked' },
]

/** Invitation status words for the badge (the shared StatusBadge knows most of them already). */
function InviteBadge({ status }: { status: InviteRow['status'] }) {
  if (status === 'PENDING') return <StatusBadge status="pending" label="Waiting" />
  if (status === 'ACCEPTED') return <StatusBadge status="active" label="Accepted" />
  if (status === 'EXPIRED') return <StatusBadge status="expired" label="Expired" />
  return <StatusBadge status="cancelled" label="Revoked" />
}

const col = createDataTableColumns<InviteRow>()

export default function InvitesPage() {
  const list = useListState(FILTERS, DEFAULT_SORT)
  const { q, status } = list.filters
  const query = useInvites({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    sort: list.sortParams,
    ...(q ? { q } : {}),
    ...(status ? { status } : {}),
  })
  const resend = useResendInvite()
  const revoke = useRevokeInvite()
  const [resending, setResending] = useState<InviteRow | null>(null)
  const [revoking, setRevoking] = useState<InviteRow | null>(null)

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('email', {
          id: 'email',
          header: 'Administrator',
          cell: (info) => (
            <div>
              <p className="font-medium text-brand-900">{info.row.original.fullName}</p>
              <p className="text-sm text-muted">{info.getValue()}</p>
            </div>
          ),
        }),
        col.accessor('communityName', {
          id: 'communityName',
          header: 'Community',
          cell: (info) => {
            const row = info.row.original
            return row.communityId ? (
              <Link
                to={paths.superadmin.community(row.communityId)}
                className="text-brand-700 underline-offset-2 hover:underline"
              >
                {info.getValue()}
              </Link>
            ) : (
              ''
            )
          },
        }),
        col.accessor('status', {
          id: 'status',
          header: 'Status',
          cell: (info) => <InviteBadge status={info.getValue()} />,
        }),
        col.accessor('invitedAt', {
          id: 'invitedAt',
          header: 'Sent',
          cell: (info) => formatDateTime(info.getValue()),
        }),
        col.accessor('expiresAt', {
          id: 'expiresAt',
          header: 'Link expires',
          cell: (info) => {
            const row = info.row.original
            return row.status === 'ACCEPTED' || row.status === 'REVOKED'
              ? ''
              : formatDate(info.getValue())
          },
        }),
        col.accessor('acceptedAt', {
          header: 'Accepted',
          enableSorting: false,
          cell: (info) => {
            const accepted = info.getValue()
            return accepted ? formatDateTime(accepted) : ''
          },
        }),
      ]),
    [],
  )

  const filtering = q !== '' || status !== ''

  return (
    <>
      <PageHeader
        title="Invites"
        description="Invitations sent to community administrators, and what became of them."
      />

      <DataTable
        label="Invitations"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(row) => row.userId}
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
          label: 'Search invitations',
        }}
        filters={
          <SimpleSelect
            aria-label="Filter by status"
            options={STATUS_OPTIONS}
            value={status || ALL}
            onValueChange={(value) => list.setFilter('status', value === ALL ? '' : value)}
          />
        }
        rowLabel={(row) => row.email}
        rowActions={(row) =>
          row.status === 'ACCEPTED' ? (
            <DropdownMenuItem disabled>Already accepted</DropdownMenuItem>
          ) : (
            <>
              <DropdownMenuItem onSelect={() => setResending(row)}>
                {row.status === 'PENDING' ? 'Send again' : 'Send a new invitation'}
              </DropdownMenuItem>
              {row.status === 'PENDING' ? (
                <DropdownMenuItem destructive onSelect={() => setRevoking(row)}>
                  Revoke
                </DropdownMenuItem>
              ) : null}
            </>
          )
        }
        emptyState={
          filtering ? (
            <EmptyState
              title="No invitations match"
              description="Try a different search or clear the filters."
              action={
                <Button
                  variant="secondary"
                  onClick={() => {
                    list.setFilter('q', '')
                    list.setFilter('status', '')
                  }}
                >
                  Clear filters
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No invitations yet"
              description="Creating a community sends its owner an invitation, which appears here."
            />
          )
        }
      />

      <ConfirmDialog
        open={resending !== null}
        onOpenChange={(open) => !open && setResending(null)}
        title={`Send an invitation to ${resending?.email ?? ''}?`}
        description="They get a new link by email. The earlier link stops working."
        confirmLabel="Send"
        onConfirm={async () => {
          if (!resending) return
          try {
            await resend.mutateAsync(resending.userId)
            notify.success(`Invitation sent to ${resending.email}`)
          } catch (error) {
            notify.apiError(error)
            throw error
          }
        }}
      />
      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(open) => !open && setRevoking(null)}
        variant="danger"
        title={`Revoke the invitation for ${revoking?.email ?? ''}?`}
        description="The link stops working. The account stays, so you can send a new invitation later."
        confirmLabel="Revoke"
        onConfirm={async () => {
          if (!revoking) return
          try {
            await revoke.mutateAsync(revoking.userId)
            notify.success('Invitation revoked')
          } catch (error) {
            notify.apiError(error)
            throw error
          }
        }}
      />
    </>
  )
}
