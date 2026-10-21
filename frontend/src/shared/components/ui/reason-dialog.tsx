import { useId, useState, type ReactNode } from 'react'
import { toApiError } from '@/lib/apiError'
import { Button } from '@/shared/components/ui/button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { Label } from '@/shared/components/ui/label'
import { Textarea } from '@/shared/components/ui/input'

interface ReasonDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: ReactNode
  confirmLabel: string
  variant?: 'default' | 'danger'
  /** The reason is stored in the audit log. The backend allows 500 characters. */
  required?: boolean
  maxLength?: number
  /** Runs with the trimmed reason. A rejection is shown in the dialog, which stays open. */
  onSubmit: (reason: string) => Promise<unknown>
}

/**
 * A destructive or sensitive action that needs a written reason (suspend, archive, cancel a
 * payment). Same contract as ConfirmDialog, plus the reason box.
 */
export function ReasonDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  variant = 'danger',
  required = true,
  maxLength = 500,
  onSubmit,
}: ReasonDialogProps) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const id = useId()
  const trimmed = reason.trim()
  const blocked = required && trimmed.length === 0

  function close(next: boolean) {
    if (busy) return
    if (!next) {
      setReason('')
      setError(null)
    }
    onOpenChange(next)
  }

  async function submit() {
    setBusy(true)
    setError(null)
    try {
      await onSubmit(trimmed)
      setReason('')
      onOpenChange(false)
    } catch (caught) {
      setError(toApiError(caught).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription asChild>
            <div>{description}</div>
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-1.5">
          <Label htmlFor={id}>{required ? 'Reason (required)' : 'Reason (optional)'}</Label>
          <Textarea
            id={id}
            value={reason}
            maxLength={maxLength}
            aria-required={required}
            aria-invalid={error !== null}
            aria-describedby={error ? `${id}-error` : undefined}
            onChange={(event) => setReason(event.target.value)}
          />
          <p className="text-xs text-muted">
            Kept in the audit log. {reason.length}/{maxLength}
          </p>
          {error ? (
            <p id={`${id}-error`} role="alert" className="text-sm text-danger">
              {error}
            </p>
          ) : null}
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="secondary" disabled={busy}>
              Cancel
            </Button>
          </DialogClose>
          <Button
            variant={variant === 'danger' ? 'danger' : 'primary'}
            loading={busy}
            disabled={blocked}
            onClick={() => void submit()}
          >
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
