import { useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { formatDateTime } from '@/lib/dates'
import { describeError } from '@/lib/errors'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { formatBytes } from '@/lib/upload'
import { Button } from '@/shared/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Input, Textarea } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { AnonymiseResult, ExportStatus, ExportView, Uuid } from '@/types'
import { useMember } from '../../hooks/useMembers'
import { useWriteAccess } from '../../hooks/usePlan'
import { useSettings } from '../../hooks/useSettings'
import {
  useAnonymiseMember,
  useDataExports,
  useExportDownloadUrl,
  useRequestDataExport,
} from '../../hooks/useSettingsActions'
import { MemberPicker } from '../MemberPicker'

const STATUS: Record<ExportStatus, { status: string; label: string }> = {
  PENDING: { status: 'pending', label: 'Waiting' },
  RUNNING: { status: 'pending', label: 'Being prepared' },
  READY: { status: 'active', label: 'Ready' },
  FAILED: { status: 'overdue', label: 'Failed' },
  EXPIRED: { status: 'suspended', label: 'Link expired' },
}

/** Download everything the community has, and erase one member's personal data. */
export function DataTab() {
  return (
    <div className="grid max-w-3xl gap-10">
      <ExportSection />
      <ErasureSection />
    </div>
  )
}

// ---- export -----------------------------------------------------------------------------------

function ExportSection() {
  const exports = useDataExports()
  const request = useRequestDataExport()
  const write = useWriteAccess()
  const busy = exports.data?.some((e) => e.status === 'PENDING' || e.status === 'RUNNING') ?? false

  async function ask() {
    try {
      await request.mutateAsync()
      notify.success('We are preparing your data. We will email you a link when it is ready.')
    } catch (error) {
      notify.apiError(error)
    }
  }

  return (
    <section aria-labelledby="export-heading">
      <h2 id="export-heading" className="font-heading text-lg font-semibold text-brand-900">
        Download all your data
      </h2>
      <p className="mt-2 text-sm text-muted">
        One file with a spreadsheet for each kind of record: members, bills, payments, receipts, the
        ledger, complaints, announcements, settings and the audit trail. We email you a link that
        works for 48 hours. You can ask for up to 3 a day.
      </p>
      <div className="mt-3">
        <Button
          onClick={() => void ask()}
          loading={request.isPending}
          disabled={busy || !write.allowed}
          {...(write.reason ? { title: write.reason } : {})}
        >
          {busy ? 'Being prepared' : 'Prepare a download'}
        </Button>
      </div>

      <div className="mt-5">
        {exports.isLoading ? (
          <Skeleton className="h-24" role="status" aria-label="Loading your downloads" />
        ) : exports.isError ? (
          <ErrorState error={exports.error} onRetry={() => void exports.refetch()} />
        ) : (exports.data ?? []).length === 0 ? (
          <p className="text-sm text-muted">You have not asked for a download yet.</p>
        ) : (
          <ul aria-label="Your downloads" className="grid gap-2">
            {(exports.data ?? []).map((item) => (
              <ExportRow key={item.id} item={item} />
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

function ExportRow({ item }: { item: ExportView }) {
  const url = useExportDownloadUrl()
  const shown = STATUS[item.status]

  async function download() {
    try {
      const { url: address } = await url.mutateAsync(item.id)
      window.location.assign(address)
    } catch (error) {
      notify.apiError(error)
    }
  }

  return (
    <li className="flex flex-wrap items-center justify-between gap-3 rounded border border-brand-100 bg-white p-3">
      <div className="min-w-0 text-sm">
        <p className="font-medium text-ink">Asked {formatDateTime(item.requestedAt)}</p>
        {item.status === 'READY' && item.sizeBytes !== null ? (
          <p className="text-muted">
            {formatBytes(item.sizeBytes)}
            {item.linkExpiresAt ? `, link works until ${formatDateTime(item.linkExpiresAt)}` : ''}
          </p>
        ) : null}
        {item.status === 'FAILED' ? (
          <p className="text-muted">It did not finish. Ask for it again.</p>
        ) : null}
      </div>
      <div className="flex items-center gap-2">
        <StatusBadge status={shown.status} label={shown.label} />
        {item.status === 'READY' ? (
          <Button size="sm" loading={url.isPending} onClick={() => void download()}>
            Download
          </Button>
        ) : null}
      </div>
    </li>
  )
}

// ---- erasure ----------------------------------------------------------------------------------

function ErasureSection() {
  const write = useWriteAccess()
  const [open, setOpen] = useState(false)
  const [done, setDone] = useState<AnonymiseResult | null>(null)

  return (
    <section aria-labelledby="erase-heading">
      <h2 id="erase-heading" className="font-heading text-lg font-semibold text-brand-900">
        Erase a member's personal data
      </h2>
      <p className="mt-2 text-sm text-muted">
        For a member who asks to be forgotten. Their name, email, phone and other personal details
        are removed for good. Their member number, bills, payments and receipts stay, so your books
        still add up. This cannot be undone.
      </p>
      <div className="mt-3">
        <Button
          variant="secondary"
          onClick={() => setOpen(true)}
          disabled={!write.allowed}
          {...(write.reason ? { title: write.reason } : {})}
        >
          Erase a member's data
        </Button>
      </div>
      {done ? <ErasureResult result={done} /> : null}
      <ErasureDialog
        open={open}
        onOpenChange={setOpen}
        onDone={(result) => {
          setDone(result)
          setOpen(false)
        }}
      />
    </section>
  )
}

function ErasureResult({ result }: { result: AnonymiseResult }) {
  const currency = useSettings().data?.currency ?? 'INR'
  return (
    <div role="status" className="mt-4 rounded border border-brand-100 bg-brand-50 p-4 text-sm">
      <p className="font-semibold text-brand-900">Member {result.memberNo} was erased.</p>
      <p className="mt-2">
        Kept: {result.kept.invoices} bills, {result.kept.payments} payments, {result.kept.receipts}{' '}
        receipts
        {result.kept.unpaidInvoices > 0
          ? `, with ${result.kept.unpaidInvoices} unpaid (${formatMoney(result.kept.outstanding, { currency })} owed)`
          : ''}
        .
      </p>
      <p className="mt-1">
        Removed: {result.removed.registrations} registration forms, {result.removed.complaints}{' '}
        complaints, {result.removed.complaintComments} comments, {result.removed.emails} emails,{' '}
        {result.removed.receiptPdfs} stored receipt files.
      </p>
    </div>
  )
}

function ErasureDialog({
  open,
  onOpenChange,
  onDone,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onDone: (result: AnonymiseResult) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        {open ? <ErasureBody onCancel={() => onOpenChange(false)} onDone={onDone} /> : null}
      </DialogContent>
    </Dialog>
  )
}

function ErasureBody({
  onCancel,
  onDone,
}: {
  onCancel: () => void
  onDone: (result: AnonymiseResult) => void
}) {
  const [memberId, setMemberId] = useState<Uuid>('')
  const member = useMember(memberId)
  const erase = useAnonymiseMember()
  const [typed, setTyped] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const target = memberId ? member.data : undefined
  const confirmed = target !== undefined && typed.trim() === target.memberNo
  const ready = confirmed && reason.trim() !== ''

  async function submit() {
    if (!target) return
    setError(null)
    try {
      onDone(
        await erase.mutateAsync({ id: target.id, memberNo: typed.trim(), reason: reason.trim() }),
      )
    } catch (failure) {
      const problem = toApiError(failure)
      setError(
        problem.code === 'MEMBER_ALREADY_ANONYMISED'
          ? describeError(problem)
          : describeError(failure),
      )
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Erase a member's personal data</DialogTitle>
        <DialogDescription>
          This cannot be undone. Choose the member, then type their member number to confirm.
        </DialogDescription>
      </DialogHeader>
      <form
        noValidate
        className="grid gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          if (ready) void submit()
        }}
      >
        <div className="grid gap-1.5">
          <Label htmlFor="erase-member">Member</Label>
          <MemberPicker
            id="erase-member"
            value={memberId}
            onChange={(id) => {
              setMemberId(id)
              setTyped('')
            }}
          />
        </div>
        {target ? (
          <>
            <div className="grid gap-1.5">
              <Label htmlFor="erase-confirm">
                Type <strong>{target.memberNo}</strong> to confirm
              </Label>
              <Input
                id="erase-confirm"
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
                autoComplete="off"
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="erase-reason">Why</Label>
              <Textarea
                id="erase-reason"
                rows={3}
                maxLength={300}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                aria-describedby="erase-reason-hint"
              />
              <p id="erase-reason-hint" className="text-xs text-muted">
                Do not put personal details here: the record of why cannot be edited.
              </p>
            </div>
          </>
        ) : null}
        {error ? (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        ) : null}
        <DialogFooter>
          <Button type="button" variant="secondary" onClick={onCancel} disabled={erase.isPending}>
            Cancel
          </Button>
          <Button type="submit" variant="danger" loading={erase.isPending} disabled={!ready}>
            Erase for good
          </Button>
        </DialogFooter>
      </form>
    </>
  )
}
