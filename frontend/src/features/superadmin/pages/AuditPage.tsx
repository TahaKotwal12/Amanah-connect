import { ChevronDown, ChevronRight, Download } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { downloadBlob } from '@/lib/download'
import { formatDateTime } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { Combobox } from '@/shared/components/ui/combobox'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Input } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { PageHeader } from '@/shared/components/ui/page-header'
import { Skeleton } from '@/shared/components/ui/skeleton'
import type { AdminAuditEntry } from '@/types'
import { exportAudit } from '../api/audit'
import { AuditDiff } from '../components/AuditDiff'
import { useCommunities } from '../hooks/useCommunities'
import { useAuditTrail } from '../hooks/useAudit'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function EntryRow({ entry }: { entry: AdminAuditEntry }) {
  const [open, setOpen] = useState(false)
  const hasDetail = entry.before !== null || entry.after !== null
  return (
    <li className="border-b border-brand-100 last:border-b-0">
      <div className="flex items-start gap-2 py-3">
        <button
          type="button"
          aria-expanded={open}
          aria-label={`${open ? 'Hide' : 'Show'} details for ${entry.summary}`}
          onClick={() => setOpen(!open)}
          className="flex size-11 shrink-0 items-center justify-center rounded text-muted hover:bg-brand-50 hover:text-ink"
        >
          {open ? (
            <ChevronDown aria-hidden="true" className="size-5" />
          ) : (
            <ChevronRight aria-hidden="true" className="size-5" />
          )}
        </button>
        <div className="grid min-w-0 flex-1 gap-0.5 sm:grid-cols-[12rem_1fr_14rem] sm:gap-4">
          <p className="text-sm text-muted">{formatDateTime(entry.at)}</p>
          <div className="min-w-0">
            <p className="font-medium text-brand-900">{entry.summary}</p>
            <p className="font-mono text-xs text-muted">{entry.action}</p>
          </div>
          <div className="min-w-0 text-sm">
            <p className="truncate">{entry.actorName ?? 'System'}</p>
            {entry.communityId ? (
              <Link
                to={paths.superadmin.community(entry.communityId)}
                className="block truncate text-brand-700 underline-offset-2 hover:underline"
              >
                {entry.communityName ?? 'Community'}
              </Link>
            ) : null}
          </div>
        </div>
      </div>
      {open ? (
        <div className="mb-3 ml-12 grid gap-3 rounded border border-brand-100 bg-brand-50 p-4">
          {hasDetail ? (
            <AuditDiff before={entry.before} after={entry.after} />
          ) : (
            <p className="text-sm text-muted">No before and after were recorded for this entry.</p>
          )}
          <dl className="grid gap-x-6 gap-y-1 text-xs text-muted sm:grid-cols-2">
            {entry.entityType ? (
              <div>
                <dt className="inline">Record: </dt>
                <dd className="inline">
                  {entry.entityType}
                  {entry.entityId ? ` ${entry.entityId}` : ''}
                </dd>
              </div>
            ) : null}
            {entry.actorRole ? (
              <div>
                <dt className="inline">Role: </dt>
                <dd className="inline">{entry.actorRole}</dd>
              </div>
            ) : null}
            {entry.ip ? (
              <div>
                <dt className="inline">From: </dt>
                <dd className="inline">{entry.ip}</dd>
              </div>
            ) : null}
            {entry.requestId ? (
              <div>
                <dt className="inline">Request: </dt>
                <dd className="inline">{entry.requestId}</dd>
              </div>
            ) : null}
          </dl>
        </div>
      ) : null}
    </li>
  )
}

export default function AuditPage() {
  const [params, setParams] = useSearchParams()
  const communityId = params.get('communityId') ?? ''
  const actor = params.get('actor') ?? ''
  const action = params.get('action') ?? ''
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const actorInvalid = actor !== '' && !UUID.test(actor)
  const [actorText, setActorText] = useState(actor)
  const [exporting, setExporting] = useState(false)

  const filters = useMemo(
    () => ({
      ...(communityId ? { communityId } : {}),
      ...(actor && !actorInvalid ? { actor } : {}),
      ...(action ? { action } : {}),
      ...(from ? { from } : {}),
      ...(to ? { to } : {}),
    }),
    [communityId, actor, actorInvalid, action, from, to],
  )
  const trail = useAuditTrail(filters)
  const communities = useCommunities({ size: 100, sort: ['name,asc'] })
  const communityOptions = (communities.data?.items ?? []).map((item) => ({
    value: item.id,
    label: item.name,
  }))
  const entries = trail.data?.pages.flatMap((page) => page.items) ?? []
  const filtering = Object.keys(filters).length > 0 || actorInvalid

  function setParam(key: string, value: string) {
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

  async function download() {
    setExporting(true)
    try {
      const result = await exportAudit(filters)
      downloadBlob('audit-trail.csv', result.blob)
      notify.success(
        result.truncated
          ? 'Downloaded the newest 100,000 rows. Narrow the dates to get the rest.'
          : `Downloaded ${result.rows ?? 'all'} rows`,
      )
    } catch (error) {
      notify.apiError(error)
    } finally {
      setExporting(false)
    }
  }

  return (
    <>
      <PageHeader
        title="Audit trail"
        description="Who did what, and when. Entries are never changed or deleted."
        actions={
          <Button variant="secondary" onClick={() => void download()} loading={exporting}>
            <Download aria-hidden="true" className="size-4" />
            Export CSV
          </Button>
        }
      />

      <form
        role="search"
        aria-label="Filter the audit trail"
        onSubmit={(event) => {
          event.preventDefault()
          setParam('actor', actorText.trim())
        }}
        className="mb-6 grid gap-4 rounded-card border border-brand-100 bg-white p-4 sm:grid-cols-2 lg:grid-cols-5"
      >
        <div className="grid gap-1.5">
          <Label htmlFor="audit-community">Community</Label>
          <Combobox
            id="audit-community"
            options={communityOptions}
            value={communityId || undefined}
            onValueChange={(value) => setParam('communityId', value)}
            placeholder="Any community"
            searchPlaceholder="Search communities"
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="audit-action">Action</Label>
          <Input
            id="audit-action"
            defaultValue={action}
            placeholder="COMMUNITY_SUSPENDED"
            autoComplete="off"
            onBlur={(event) => setParam('action', event.target.value.trim().toUpperCase())}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="audit-actor">Person (user id)</Label>
          <Input
            id="audit-actor"
            value={actorText}
            aria-invalid={actorInvalid}
            aria-describedby={actorInvalid ? 'audit-actor-error' : undefined}
            onChange={(event) => setActorText(event.target.value)}
            onBlur={() => setParam('actor', actorText.trim())}
            autoComplete="off"
          />
          {actorInvalid ? (
            <p id="audit-actor-error" role="alert" className="text-sm text-danger">
              That is not a valid user id.
            </p>
          ) : null}
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="audit-from">From</Label>
          <DatePicker
            id="audit-from"
            value={from || undefined}
            onValueChange={(value) => setParam('from', value ?? '')}
            {...(to ? { max: to } : {})}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="audit-to">To</Label>
          <DatePicker
            id="audit-to"
            value={to || undefined}
            onValueChange={(value) => setParam('to', value ?? '')}
            {...(from ? { min: from } : {})}
          />
        </div>
        {filtering ? (
          <div className="sm:col-span-2 lg:col-span-5">
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setActorText('')
                setParams(new URLSearchParams(), { replace: true })
              }}
            >
              Clear filters
            </Button>
          </div>
        ) : null}
      </form>

      {trail.isError ? (
        <ErrorState
          headingLevel={2}
          error={trail.error}
          onRetry={() => void trail.refetch()}
          retrying={trail.isFetching}
        />
      ) : trail.isLoading ? (
        <div role="status" aria-label="Loading the audit trail" className="grid gap-2">
          <Skeleton className="h-16" />
          <Skeleton className="h-16" />
          <Skeleton className="h-16" />
        </div>
      ) : entries.length === 0 ? (
        <EmptyState
          title={filtering ? 'Nothing matches those filters' : 'Nothing has been recorded yet'}
          description={filtering ? 'Try a wider range of dates, or clear the filters.' : undefined}
        />
      ) : (
        <section aria-label="Audit entries">
          <ol className="rounded-card border border-brand-100 bg-white px-2">
            {entries.map((entry) => (
              <EntryRow key={entry.id} entry={entry} />
            ))}
          </ol>
          <div className="mt-4 flex justify-center">
            {trail.hasNextPage ? (
              <Button
                variant="secondary"
                onClick={() => void trail.fetchNextPage()}
                loading={trail.isFetchingNextPage}
              >
                Load older entries
              </Button>
            ) : (
              <p className="text-sm text-muted">That is everything that matches.</p>
            )}
          </div>
        </section>
      )}
    </>
  )
}
