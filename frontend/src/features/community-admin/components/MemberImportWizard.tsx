import { useId, useState } from 'react'
import { isApiError, toApiError } from '@/lib/apiError'
import { downloadText } from '@/lib/download'
import { notify } from '@/lib/notify'
import { FileReportBlock } from '@/shared/components/import/FileReportBlock'
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
import { useConfirmMemberImport, useUploadMemberImport } from '../hooks/useMembers'

const STEPS = [
  { id: 'upload', label: 'Upload the file' },
  { id: 'check', label: 'Check results' },
  { id: 'done', label: 'Finished' },
] as const

const MAX_BYTES = 10 * 1024 * 1024
const TEMPLATE =
  'member_no,full_name,email,phone,group,status,joined_on,consent_email\n' +
  'M-0001,Asha Rao,asha@example.com,+91 98765 43210,Block A,ACTIVE,2024-04-01,yes\n'

interface MemberImportWizardProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Bring members in from a CSV file: every row is checked first, and nothing is saved until you confirm. */
export function MemberImportWizard({ open, onOpenChange }: MemberImportWizardProps) {
  const upload = useUploadMemberImport()
  const confirm = useConfirmMemberImport()
  const inputId = useId()
  const [file, setFile] = useState<File | null>(null)
  const [batchId, setBatchId] = useState<Uuid>(() => crypto.randomUUID())
  const [report, setReport] = useState<ImportReport | null>(null)
  const [done, setDone] = useState(false)
  const [skipInvalid, setSkipInvalid] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const step = done ? 2 : report ? 1 : 0
  const busy = upload.isPending || confirm.isPending

  function reset() {
    setFile(null)
    setReport(null)
    setDone(false)
    setSkipInvalid(false)
    setError(null)
    setBatchId(crypto.randomUUID())
  }

  function close(next: boolean) {
    if (busy) return
    if (!next) reset()
    onOpenChange(next)
  }

  function choose(chosen: File | undefined) {
    setError(null)
    if (chosen && chosen.size > MAX_BYTES) {
      setError(`${chosen.name} is larger than 10 MB. Split it into smaller files.`)
      return
    }
    setFile(chosen ?? null)
  }

  async function check() {
    if (!file) return
    setError(null)
    try {
      setReport(await upload.mutateAsync({ batchId, file }))
    } catch (caught) {
      setError(toApiError(caught).message)
    }
  }

  async function apply() {
    if (!report) return
    setError(null)
    try {
      await confirm.mutateAsync({ batchId: report.batchId, skipInvalidRows: skipInvalid })
      setDone(true)
      notify.success('Members imported')
    } catch (caught) {
      // A plan limit gets its own dialog; the wizard steps aside for it.
      if (isApiError(caught) && caught.code === 'PLAN_LIMIT_EXCEEDED') return
      setError(toApiError(caught).message)
    }
  }

  const invalid = report?.members.invalidRows ?? 0
  const canConfirm =
    report !== null &&
    report.confirmable &&
    report.blockingErrors.length === 0 &&
    (invalid === 0 || skipInvalid)

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="max-h-[92vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Import members from a CSV file</DialogTitle>
          <DialogDescription>
            You see every problem before anything is saved. Members who are already in the list are
            recognised by their member number.
          </DialogDescription>
        </DialogHeader>
        <Stepper steps={STEPS} current={step} label="Import members" />

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
            <div className="grid gap-1">
              <Label htmlFor={inputId}>CSV file</Label>
              <input
                id={inputId}
                type="file"
                accept=".csv,text/csv"
                aria-describedby={`${inputId}-hint`}
                onChange={(event) => choose(event.target.files?.[0])}
                className="block w-full text-sm file:mr-3 file:min-h-11 file:rounded file:border file:border-brand-600 file:bg-white file:px-4 file:font-medium file:text-brand-700"
              />
              <p id={`${inputId}-hint`} className="text-xs text-muted">
                Columns: member_no, full_name, email, phone, group, status, joined_on,
                consent_email. Only full_name is required.
              </p>
            </div>
            <Button
              variant="ghost"
              className="w-fit"
              onClick={() => downloadText('members-template.csv', TEMPLATE)}
            >
              Download a template
            </Button>
            <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
              <Button variant="secondary" onClick={() => close(false)}>
                Cancel
              </Button>
              <Button onClick={() => void check()} disabled={!file} loading={upload.isPending}>
                Check the file
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
                ? `, to a plan that allows ${report.summary.planMemberLimit.toLocaleString('en-IN')} (you have ${report.summary.currentMembers.toLocaleString('en-IN')} now)`
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
            {invalid > 0 ? (
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
                onClick={() => void apply()}
                disabled={!canConfirm}
                loading={confirm.isPending}
              >
                Import now
              </Button>
            </div>
          </div>
        ) : null}

        {step === 2 ? (
          <div className="grid gap-4">
            <p role="status" className="text-sm">
              The members are in the list.
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
