import { useId, useState, type ReactNode } from 'react'
import { Button } from './button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './dialog'
import { Input } from './input'
import { Label } from './label'

interface ConfirmDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: ReactNode
  confirmLabel?: string
  cancelLabel?: string
  /** "danger" is for destructive or irreversible actions. */
  variant?: 'default' | 'danger'
  /**
   * Make the person type this exact text to enable the button, for the worst actions
   * (deleting data, suspending a community): confirmText="Lotus Residents".
   */
  confirmText?: string
  /** Runs on confirm. The dialog stays open with a spinner until it settles, and stays open if it throws. */
  onConfirm: () => void | Promise<void>
}

export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'default',
  confirmText,
  onConfirm,
}: ConfirmDialogProps) {
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const inputId = useId()
  const matches = confirmText === undefined || typed === confirmText

  async function confirm() {
    setBusy(true)
    try {
      await onConfirm()
      onOpenChange(false)
    } catch {
      // The caller reports the failure (toast or inline); keep the dialog so nothing is lost.
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (busy) return
        if (!next) setTyped('')
        onOpenChange(next)
      }}
    >
      <DialogContent role="alertdialog">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription asChild>
            <div>{description}</div>
          </DialogDescription>
        </DialogHeader>
        {confirmText !== undefined ? (
          <div className="grid gap-1.5">
            <Label htmlFor={inputId}>
              Type <strong className="font-semibold">{confirmText}</strong> to confirm
            </Label>
            <Input
              id={inputId}
              value={typed}
              autoComplete="off"
              onChange={(event) => {
                setTyped(event.target.value)
              }}
            />
          </div>
        ) : null}
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="secondary" disabled={busy}>
              {cancelLabel}
            </Button>
          </DialogClose>
          <Button
            variant={variant === 'danger' ? 'danger' : 'primary'}
            loading={busy}
            disabled={!matches}
            onClick={() => void confirm()}
          >
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
