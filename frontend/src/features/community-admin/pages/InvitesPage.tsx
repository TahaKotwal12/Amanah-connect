import { Mail, QrCode } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { isApiError } from '@/lib/apiError'
import { formatDate, formatDateTime } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { Pagination } from '@/shared/components/ui/pagination'
import { ReasonDialog } from '@/shared/components/ui/reason-dialog'
import { SimpleSelect } from '@/shared/components/ui/select'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/components/ui/tabs'
import { useListState } from '@/shared/hooks/useListState'
import type { InviteView, RegistrationStatus, RegistrationView } from '@/types'
import { CreateInviteDialog, InviteByEmailDialog } from '../components/InviteDialogs'
import {
  useApproveRegistration,
  useInvites,
  useRegistrations,
  useRejectRegistration,
  useRevokeInvite,
} from '../hooks/useInvites'
import { useMemberCounts } from '../hooks/useMembers'
import { useWriteAccess } from '../hooks/usePlan'

const ALL = 'all'
const STATE_OPTIONS = [
  { value: ALL, label: 'Any state' },
  { value: 'ACTIVE', label: 'Working' },
  { value: 'EXPIRED', label: 'Expired' },
  { value: 'USED_UP', label: 'Used up' },
  { value: 'REVOKED', label: 'Revoked' },
]
const REGISTRATION_FILTERS: ReadonlyArray<{
  value: RegistrationStatus | typeof ALL
  label: string
}> = [
  { value: 'PENDING', label: 'Waiting' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: ALL, label: 'All' },
]
const REG_PAGE_SIZE = 20

function InviteStateBadge({ state }: { state: string }) {
  if (state === 'ACTIVE') return <StatusBadge status="active" label="Working" />
  if (state === 'USED_UP') return <StatusBadge status="closed" label="Used up" />
  if (state === 'EXPIRED') return <StatusBadge status="expired" />
  return <StatusBadge status="cancelled" label="Revoked" />
}

const col = createDataTableColumns<InviteView>()

// ---- invite links -------------------------------------------------------------------------------

function InviteLinks() {
  const list = useListState(['state'] as const, ['createdAt,desc'])
  const { state } = list.filters
  const query = useInvites({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    ...(state ? { state } : {}),
  })
  const revoke = useRevokeInvite()
  const [revoking, setRevoking] = useState<InviteView | null>(null)
  const write = useWriteAccess()

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('invitedEmail', {
          header: 'For',
          enableSorting: false,
          cell: (info) => info.getValue() ?? 'Anyone with the link',
        }),
        col.accessor('state', {
          header: 'State',
          enableSorting: false,
          cell: (info) => <InviteStateBadge state={info.getValue()} />,
        }),
        col.accessor((row) => `${row.usedCount} of ${row.maxUses}`, {
          id: 'uses',
          header: 'Used',
          enableSorting: false,
        }),
        col.accessor('defaultGroup', {
          header: 'Group',
          enableSorting: false,
          cell: (info) => info.getValue() ?? '',
        }),
        col.accessor('expiresAt', {
          header: 'Expires',
          enableSorting: false,
          cell: (info) => formatDate(info.getValue()),
        }),
        col.accessor('createdAt', {
          header: 'Created',
          enableSorting: false,
          cell: (info) => formatDateTime(info.getValue()),
        }),
      ]),
    [],
  )

  return (
    <>
      <p className="mb-4 text-sm text-muted">
        A link is shown only once, when you make it, so copy it or print its card then. Anyone who
        registers through it waits in the Registrations tab for you to approve them.
      </p>
      <DataTable
        label="Invite links"
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
        filters={
          <SimpleSelect
            aria-label="Filter by state"
            options={STATE_OPTIONS}
            value={state || ALL}
            onValueChange={(value) => list.setFilter('state', value === ALL ? '' : value)}
          />
        }
        rowLabel={(row) => row.invitedEmail ?? `invite made ${formatDate(row.createdAt)}`}
        rowActions={(row) =>
          row.state === 'ACTIVE' ? (
            <DropdownMenuItem
              destructive
              disabled={!write.allowed}
              onSelect={() => setRevoking(row)}
            >
              Revoke
            </DropdownMenuItem>
          ) : (
            <DropdownMenuItem disabled>Nothing to do</DropdownMenuItem>
          )
        }
        emptyState={
          <EmptyState
            title={state ? 'No invite links in that state' : 'No invite links yet'}
            description="Make a link or a QR card for people to register themselves."
          />
        }
      />

      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(open) => !open && setRevoking(null)}
        variant="danger"
        title="Revoke this invite link?"
        description="It stops working at once. People who already registered through it are not affected."
        confirmLabel="Revoke"
        onConfirm={async () => {
          if (!revoking) return
          try {
            await revoke.mutateAsync(revoking.id)
            notify.success('Invite link revoked')
          } catch (error) {
            notify.apiError(error)
            throw error
          }
        }}
      />
    </>
  )
}

// ---- registrations ----------------------------------------------------------------------------

function Registrations() {
  const write = useWriteAccess()
  const [status, setStatus] = useState<RegistrationStatus | typeof ALL>('PENDING')
  const [page, setPage] = useState(0)
  const query = useRegistrations({
    page,
    size: REG_PAGE_SIZE,
    sort: ['createdAt,asc'],
    ...(status !== ALL ? { status } : {}),
  })
  const approve = useApproveRegistration()
  const reject = useRejectRegistration()
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const [rejecting, setRejecting] = useState<RegistrationView | null>(null)
  const [sharing, setSharing] = useState<RegistrationView | null>(null)
  const [bulkOpen, setBulkOpen] = useState(false)

  const rows = query.data?.items ?? []
  const pendingRows = rows.filter((row) => row.status === 'PENDING')
  /** A registration whose address another member already uses needs a decision of its own. */
  const bulkable = pendingRows.filter((row) => row.emailUsedByMemberNo === null)
  const chosen = bulkable.filter((row) => selected.has(row.id))

  function toggle(id: string, on: boolean) {
    setSelected((previous) => {
      const next = new Set(previous)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })
  }

  async function approveOne(row: RegistrationView, allowDuplicateEmail = false) {
    try {
      const done = await approve.mutateAsync({
        id: row.id,
        ...(allowDuplicateEmail ? { body: { allowDuplicateEmail: true } } : {}),
      })
      notify.success(`${row.fullName} is now member ${done.memberNo ?? ''}`.trim())
    } catch (error) {
      // A plan limit has its own dialog; everything else is explained here.
      if (!(isApiError(error) && error.code === 'PLAN_LIMIT_EXCEEDED')) notify.apiError(error)
      throw error
    }
  }

  async function approveSelected() {
    let approved = 0
    for (const row of chosen) {
      try {
        await approve.mutateAsync({ id: row.id })
        approved += 1
      } catch (error) {
        // Stop at the first refusal (usually the plan limit) and say how far it got.
        if (!(isApiError(error) && error.code === 'PLAN_LIMIT_EXCEEDED')) notify.apiError(error)
        break
      }
    }
    setSelected(new Set())
    if (approved > 0) {
      notify.success(approved === 1 ? '1 member approved' : `${approved} members approved`)
    }
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <SimpleSelect
          aria-label="Show registrations"
          options={REGISTRATION_FILTERS.map(({ value, label }) => ({ value, label }))}
          value={status}
          onValueChange={(value) => {
            setStatus(value as RegistrationStatus | typeof ALL)
            setSelected(new Set())
            setPage(0)
          }}
          className="w-44"
        />
        {status === 'PENDING' && bulkable.length > 0 ? (
          <>
            <Checkbox
              label="Select all that can be approved"
              checked={chosen.length === bulkable.length && bulkable.length > 0}
              onCheckedChange={(checked) =>
                setSelected(checked === true ? new Set(bulkable.map((row) => row.id)) : new Set())
              }
            />
            <Button
              disabled={chosen.length === 0 || !write.allowed}
              onClick={() => setBulkOpen(true)}
            >
              {chosen.length > 0 ? `Approve ${chosen.length} selected` : 'Approve selected'}
            </Button>
          </>
        ) : null}
      </div>

      {query.isError ? (
        <ErrorState
          headingLevel={3}
          error={query.error}
          onRetry={() => void query.refetch()}
          retrying={query.isFetching}
        />
      ) : query.isLoading ? (
        <Skeleton className="h-48" role="status" aria-label="Loading registrations" />
      ) : rows.length === 0 ? (
        <EmptyState
          title={status === 'PENDING' ? 'Nobody is waiting' : 'No registrations here'}
          description="People who register through an invite link wait here for your approval."
        />
      ) : (
        <>
          <ul
            aria-label="Registrations"
            className="divide-y divide-brand-100 rounded-card border border-brand-100 bg-white"
          >
            {rows.map((row) => (
              <li key={row.id} className="flex flex-wrap items-start gap-3 p-4">
                {status === 'PENDING' ? (
                  <div className="pt-1">
                    <Checkbox
                      aria-label={`Select ${row.fullName}`}
                      disabled={row.emailUsedByMemberNo !== null}
                      checked={selected.has(row.id)}
                      onCheckedChange={(checked) => toggle(row.id, checked === true)}
                    />
                  </div>
                ) : null}
                <div className="min-w-0 flex-1">
                  <p className="font-medium text-brand-900">{row.fullName}</p>
                  <p className="text-sm text-muted">
                    {row.email}
                    {row.phone ? `, ${row.phone}` : ''}
                    {row.group ? `, ${row.group}` : ''}
                  </p>
                  <p className="text-xs text-muted">Registered {formatDateTime(row.createdAt)}</p>
                  {row.emailUsedByMemberNo ? (
                    <p className="mt-1 text-sm text-warning">
                      This address is already used by member {row.emailUsedByMemberNo}.
                    </p>
                  ) : null}
                  {row.status === 'REJECTED' && row.rejectionReason ? (
                    <p className="mt-1 text-sm">Reason: {row.rejectionReason}</p>
                  ) : null}
                  {row.status === 'APPROVED' && row.memberNo ? (
                    <p className="mt-1 text-sm">Member {row.memberNo}</p>
                  ) : null}
                </div>
                <div className="flex items-center gap-2">
                  <StatusBadge status={row.status} />
                  {row.status === 'PENDING' ? (
                    <>
                      <Button
                        size="sm"
                        disabled={!write.allowed}
                        onClick={() =>
                          row.emailUsedByMemberNo
                            ? setSharing(row)
                            : void approveOne(row).catch(() => undefined)
                        }
                        aria-label={`Approve ${row.fullName}`}
                      >
                        Approve
                      </Button>
                      <Button
                        size="sm"
                        variant="secondary"
                        disabled={!write.allowed}
                        onClick={() => setRejecting(row)}
                        aria-label={`Reject ${row.fullName}`}
                      >
                        Reject
                      </Button>
                    </>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
          <Pagination
            className="mt-4"
            page={page}
            pageSize={REG_PAGE_SIZE}
            total={query.data?.total ?? 0}
            onPageChange={setPage}
            label="Registrations pages"
          />
        </>
      )}

      <ReasonDialog
        open={rejecting !== null}
        onOpenChange={(open) => !open && setRejecting(null)}
        title={`Reject ${rejecting?.fullName ?? 'this registration'}?`}
        description="They are told it was not approved. The reason is kept for your records."
        confirmLabel="Reject"
        onSubmit={async (reason) => {
          if (!rejecting) return
          await reject.mutateAsync({ id: rejecting.id, reason })
          notify.success(`${rejecting.fullName} was rejected`)
        }}
      />
      <ConfirmDialog
        open={sharing !== null}
        onOpenChange={(open) => !open && setSharing(null)}
        title="Approve with a shared address?"
        description={
          sharing
            ? `${sharing.email} is already used by member ${sharing.emailUsedByMemberNo ?? ''}. If they share it, for example in one household, approve anyway.`
            : ''
        }
        confirmLabel="Approve anyway"
        onConfirm={() => (sharing ? approveOne(sharing, true) : undefined)}
      />
      <ConfirmDialog
        open={bulkOpen}
        onOpenChange={setBulkOpen}
        title={`Approve ${chosen.length} ${chosen.length === 1 ? 'registration' : 'registrations'}?`}
        description="Each becomes a member, and is counted against your plan. If the plan's limit is reached, the rest stay waiting."
        confirmLabel="Approve"
        onConfirm={approveSelected}
      />
    </>
  )
}

export default function InvitesPage() {
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') === 'registrations' ? 'registrations' : 'links'
  const counts = useMemberCounts()
  const pending = counts.data?.pendingRegistrations ?? 0
  const write = useWriteAccess()
  const [linkOpen, setLinkOpen] = useState(false)
  const [emailOpen, setEmailOpen] = useState(false)

  return (
    <>
      <PageHeader
        title="Invites and registrations"
        description="Let people register themselves, then approve them."
        actions={
          <>
            <Button onClick={() => setLinkOpen(true)} disabled={!write.allowed}>
              <QrCode aria-hidden="true" className="size-4" />
              Create invite link
            </Button>
            <Button
              variant="secondary"
              onClick={() => setEmailOpen(true)}
              disabled={!write.allowed}
            >
              <Mail aria-hidden="true" className="size-4" />
              Invite by email
            </Button>
          </>
        }
      />
      {write.reason ? <p className="-mt-3 mb-4 text-sm text-muted">{write.reason}</p> : null}

      <Tabs
        value={tab}
        onValueChange={(next) =>
          setParams(
            (previous) => {
              const updated = new URLSearchParams(previous)
              if (next === 'links') updated.delete('tab')
              else updated.set('tab', next)
              return updated
            },
            { replace: true },
          )
        }
      >
        <TabsList aria-label="Invites and registrations">
          <TabsTrigger value="links">Invite links</TabsTrigger>
          <TabsTrigger value="registrations">
            Registrations
            {pending > 0 ? (
              <span className="ml-2 rounded bg-gold-50 px-1.5 text-xs font-semibold text-gold-600">
                <span className="sr-only">, </span>
                {pending} waiting
              </span>
            ) : null}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="links">
          <InviteLinks />
        </TabsContent>
        <TabsContent value="registrations">
          <Registrations />
        </TabsContent>
      </Tabs>

      <CreateInviteDialog open={linkOpen} onOpenChange={setLinkOpen} />
      <InviteByEmailDialog open={emailOpen} onOpenChange={setEmailOpen} />
    </>
  )
}
