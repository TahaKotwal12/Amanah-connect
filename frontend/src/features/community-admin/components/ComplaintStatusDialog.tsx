import { useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { Textarea } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/shared/components/ui/radio-group'
import type { ComplaintStatus, ComplaintView } from '@/types'
import { COMPLAINT_STATUSES, describeEmailOutcome, statusLabel } from '../complaintSchemas'
import { useChangeComplaintStatus } from '../hooks/useComplaints'

interface Props {
  complaint: ComplaintView | null
  /** The status to start on, for example the column a card was moved to. */
  initial?: ComplaintStatus | undefined
  onClose: () => void
}

/** Change a complaint's status with an optional note, and optionally email the member about it. */
export function ComplaintStatusDialog({ complaint, initial, onClose }: Props) {
  return (
    <Dialog open={complaint !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        {complaint ? (
          <StatusForm
            key={complaint.id}
            complaint={complaint}
            initial={initial}
            onClose={onClose}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function StatusForm({
  complaint,
  initial,
  onClose,
}: {
  complaint: ComplaintView
  initial: ComplaintStatus | undefined
  onClose: () => void
}) {
  const change = useChangeComplaintStatus()
  const [status, setStatus] = useState<ComplaintStatus>(initial ?? complaint.status)
  const [note, setNote] = useState('')
  const [emailMember, setEmailMember] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const canEmail = complaint.memberId !== null

  async function submit() {
    setError(null)
    try {
      const done = await change.mutateAsync({
        id: complaint.id,
        body: {
          status,
          ...(note.trim() ? { note: note.trim() } : {}),
          ...(canEmail && emailMember ? { notifyMember: true } : {}),
        },
      })
      notify.success(`Moved to ${statusLabel(done.status)}`)
      const outcome = describeEmailOutcome(done.emailOutcome)
      if (outcome) notify.info(outcome)
      onClose()
    } catch (caught) {
      setError(toApiError(caught).message)
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Change status</DialogTitle>
        <DialogDescription>{complaint.subject}</DialogDescription>
      </DialogHeader>
      {error ? (
        <p
          role="alert"
          className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
        >
          {error}
        </p>
      ) : null}
      <RadioGroup
        value={status}
        onValueChange={(value) => setStatus(value as ComplaintStatus)}
        aria-label="New status"
        className="grid-cols-2"
      >
        {COMPLAINT_STATUSES.map((option) => (
          <RadioGroupItem key={option.value} value={option.value} label={option.label} />
        ))}
      </RadioGroup>
      <div className="grid gap-1.5">
        <Label htmlFor="status-note">Note (optional)</Label>
        <Textarea
          id="status-note"
          rows={3}
          maxLength={2000}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
        <p className="text-xs text-muted">
          {emailMember && canEmail
            ? 'This note will be visible to the member and emailed to them.'
            : 'Without emailing, the note is kept as an internal note.'}
        </p>
      </div>
      <Checkbox
        label="Notify the member by email"
        description={
          canEmail
            ? 'Only if the member has an address and agreed to email.'
            : 'This complaint is not about a member, so there is nobody to email.'
        }
        checked={canEmail && emailMember}
        disabled={!canEmail}
        onCheckedChange={(checked) => setEmailMember(checked === true)}
      />
      <DialogFooter>
        <Button variant="secondary" onClick={onClose} disabled={change.isPending}>
          Cancel
        </Button>
        <Button
          onClick={() => void submit()}
          loading={change.isPending}
          disabled={status === complaint.status && note.trim() === ''}
        >
          Save status
        </Button>
      </DialogFooter>
    </>
  )
}
