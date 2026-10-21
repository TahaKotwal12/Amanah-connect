import { useId, useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { Label } from '@/shared/components/ui/label'
import { Stepper } from '@/shared/components/ui/stepper'
import type { ImportReport, Uuid } from '@/types'
import { FileReportBlock } from '@/shared/components/import/FileReportBlock'
import { useConfirmImport, useUploadImport } from '../hooks/useCommunities'

const STEPS = [
  { id: 'upload', label: 'Upload files' },
  { id: 'check', label: 'Check results' },
  { id: 'done', label: 'Finished' },
] as const

const FILE_KINDS = [
  {
    key: 'members',
    label: 'Members',
    hint: 'Columns: member_no, full_name, email, phone, group, status, joined_on, consent_email',
  },
  {
    key: 'openingBalances',
    label: 'Opening balances',
    hint: 'Columns: category, type, amount, as_of, description',
  },
  {
    key: 'openInvoices',
    label: 'Open invoices',
    hint: 'Columns: member_no, invoice_no, kind, period, amount, due_date',
  },
] as const
type FileKey = (typeof FILE_KINDS)[number]['key']

const MAX_BYTES = 10 * 1024 * 1024

interface ImportWizardProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  communityId: Uuid
  communityName: string
}

/** Migration import: upload CSV files, review the dry run (nothing is saved), then confirm. */
export function ImportWizard({
  open,
  onOpenChange,
  communityId,
  communityName,
}: ImportWizardProps) {
  const upload = useUploadImport(communityId)
  const confirm = useConfirmImport(communityId)
  const baseId = useId()
  const [files, setFiles] = useState<Partial<Record<FileKey, File>>>({})
  const [batchId, setBatchId] = useState<Uuid>(() => crypto.randomUUID())
  const [report, setReport] = useState<ImportReport | null>(null)
  const [result, setResult] = useState<ImportReport | null>(null)
  const [skipInvalid, setSkipInvalid] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const step = result ? 2 : report ? 1 : 0
  const busy = upload.isPending || confirm.isPending
  const chosen = FILE_KINDS.filter(({ key }) => files[key] !== undefined)

  function reset() {
    setFiles({})
    setReport(null)
    setResult(null)
    setSkipInvalid(false)
    setError(null)
    setBatchId(crypto.randomUUID())
  }

  function close(next: boolean) {
    if (busy) return
    if (!next) reset()
    onOpenChange(next)
  }

  function choose(key: FileKey, file: File | undefined) {
    setError(null)
    if (file && file.size > MAX_BYTES) {
      setError(`${file.name} is larger than 10 MB. Split it into smaller files.`)
      return
    }
    setFiles((previous) => {
      const next = { ...previous }
      if (file) next[key] = file
      else delete next[key]
      return next
    })
  }

  async function runDryRun() {
    setError(null)
    try {
      setReport(await upload.mutateAsync({ batchId, files }))
    } catch (caught) {
      setError(toApiError(caught).message)
    }
  }

  async function applyImport() {
    if (!report) return
    setError(null)
    try {
      const done = await confirm.mutateAsync({
        batchId: report.batchId,
        skipInvalidRows: skipInvalid,
      })
      setResult(done)
      notify.success('Import finished')
    } catch (caught) {
      setError(toApiError(caught).message)
    }
  }

  const invalidTotal = report
    ? report.members.invalidRows +
      report.openingBalances.invalidRows +
      report.openInvoices.invalidRows
    : 0
  const canConfirm =
    report !== null &&
    report.confirmable &&
    report.blockingErrors.length === 0 &&
    (invalidTotal === 0 || skipInvalid)

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="max-h-[92vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Import into {communityName}</DialogTitle>
          <DialogDescription>
            Bring in members, opening balances and open invoices from CSV files. You see every
            problem before anything is saved.
          </DialogDescription>
        </DialogHeader>
        <Stepper steps={STEPS} current={step} label="Import" />

        {error ? (
          <p
            role="alert"
            className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
          >
            {error}
          </p>
        ) : null}

        {step === 0 ? (
          <div className="grid gap-4">
            {FILE_KINDS.map(({ key, label, hint }) => {
              const id = `${baseId}-${key}`
              return (
                <div key={key} className="grid gap-1">
                  <Label htmlFor={id}>{label} (optional)</Label>
                  <input
                    id={id}
                    type="file"
                    accept=".csv,text/csv"
                    aria-describedby={`${id}-hint`}
                    onChange={(event) => choose(key, event.target.files?.[0])}
                    className="block w-full text-sm file:mr-3 file:min-h-11 file:rounded file:border file:border-brand-600 file:bg-white file:px-4 file:font-medium file:text-brand-700"
                  />
                  <p id={`${id}-hint`} className="text-xs text-muted">
                    {hint}
                  </p>
                </div>
              )
            })}
            <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
              <Button variant="secondary" onClick={() => close(false)}>
                Cancel
              </Button>
              <Button
                onClick={() => void runDryRun()}
                disabled={chosen.length === 0}
                loading={upload.isPending}
              >
                Check the files
              </Button>
            </div>
          </div>
        ) : null}

        {step === 1 && report ? (
          <div className="grid gap-5">
            <p className="rounded border border-brand-100 bg-brand-50 p-3 text-sm">
              <strong>Nothing has been saved yet.</strong>{' '}
              {report.summary.validRows.toLocaleString('en-IN')} rows are ready,{' '}
              {report.summary.invalidRows.toLocaleString('en-IN')} have problems.{' '}
              {report.summary.newMembers.toLocaleString('en-IN')} new members would be added
              {report.summary.planMemberLimit !== null
                ? `, to a plan that allows ${report.summary.planMemberLimit.toLocaleString('en-IN')} (${report.summary.currentMembers.toLocaleString('en-IN')} today)`
                : ''}
              .
            </p>
            {report.blockingErrors.map((message) => (
              <p key={message} role="alert" className="text-sm font-medium text-danger">
                {message}
              </p>
            ))}
            {report.warnings.map((message) => (
              <p key={message} className="text-sm text-warning">
                {message}
              </p>
            ))}
            <FileReportBlock title="Members" report={report.members} />
            <FileReportBlock title="Opening balances" report={report.openingBalances} />
            <FileReportBlock title="Open invoices" report={report.openInvoices} />
            {invalidTotal > 0 ? (
              <Checkbox
                label="Import the rows that are ready and skip the ones with problems"
                checked={skipInvalid}
                onCheckedChange={(checked) => setSkipInvalid(checked === true)}
              />
            ) : null}
            <div className="flex justify-between gap-2 border-t border-brand-100 pt-4">
              <Button variant="secondary" onClick={reset} disabled={busy}>
                Start again
              </Button>
              <Button
                onClick={() => void applyImport()}
                disabled={!canConfirm}
                loading={confirm.isPending}
              >
                Import now
              </Button>
            </div>
          </div>
        ) : null}

        {step === 2 && result ? (
          <div className="grid gap-4">
            <p role="status" className="text-sm">
              The import is finished and the data is in {communityName}.
            </p>
            <div className="flex justify-end">
              <Button onClick={() => close(false)}>Close</Button>
            </div>
          </div>
        ) : null}
      </DialogContent>
    </Dialog>
  )
}
