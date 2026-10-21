import { useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { Input, Textarea } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { SimpleSelect } from '@/shared/components/ui/select'
import type { Priority, Uuid } from '@/types'
import { PRIORITIES } from '../complaintSchemas'
import { useAttachment } from '../hooks/useAttachment'
import { useCreateThread } from '../hooks/useSupport'
import { AttachmentField } from './AttachmentField'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Called with the new conversation's id so the page can open it. */
  onCreated: (id: Uuid) => void
}

/** Start a conversation with the support team. */
export function NewThreadDialog({ open, onOpenChange, onCreated }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92vh] overflow-y-auto">
        {/* Mounted only while open, so every opening starts empty. */}
        <NewThreadForm onClose={() => onOpenChange(false)} onCreated={onCreated} />
      </DialogContent>
    </Dialog>
  )
}

function NewThreadForm({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (id: Uuid) => void
}) {
  const create = useCreateThread()
  const attachment = useAttachment()
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [priority, setPriority] = useState<Priority>('MEDIUM')
  const [errors, setErrors] = useState<{ subject?: string; body?: string }>({})
  const [formError, setFormError] = useState<string | null>(null)
  const busy = create.isPending || attachment.progress !== null

  async function submit() {
    const next: { subject?: string; body?: string } = {}
    if (subject.trim() === '') next.subject = 'Enter a subject'
    if (body.trim() === '') next.body = 'Describe what you need help with'
    setErrors(next)
    if (next.subject || next.body) return
    setFormError(null)
    try {
      const uploaded = await attachment.upload()
      const made = await create.mutateAsync({
        subject: subject.trim(),
        body: body.trim(),
        priority,
        ...uploaded,
      })
      notify.success('Sent to the support team')
      onCreated(made.id)
    } catch (caught) {
      setFormError(
        caught instanceof Error && !('code' in caught)
          ? caught.message
          : toApiError(caught).message,
      )
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Start a conversation</DialogTitle>
        <DialogDescription>
          The Amanah Connect team replies here, and by email to the person who started it.
        </DialogDescription>
      </DialogHeader>
      {formError ? (
        <p
          role="alert"
          className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
        >
          {formError}
        </p>
      ) : null}
      <div className="grid gap-1.5">
        <Label htmlFor="thread-subject">Subject</Label>
        <Input
          id="thread-subject"
          value={subject}
          maxLength={200}
          aria-invalid={errors.subject !== undefined}
          onChange={(event) => setSubject(event.target.value)}
        />
        {errors.subject ? (
          <p role="alert" className="text-sm text-danger">
            {errors.subject}
          </p>
        ) : null}
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor="thread-priority">How urgent is it?</Label>
        <SimpleSelect
          id="thread-priority"
          options={PRIORITIES}
          value={priority}
          onValueChange={(value) => setPriority(value as Priority)}
        />
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor="thread-body">Message</Label>
        <Textarea
          id="thread-body"
          rows={5}
          maxLength={5000}
          value={body}
          aria-invalid={errors.body !== undefined}
          onChange={(event) => setBody(event.target.value)}
        />
        {errors.body ? (
          <p role="alert" className="text-sm text-danger">
            {errors.body}
          </p>
        ) : null}
      </div>
      <AttachmentField attachment={attachment} disabled={busy} inputId="thread-attachment" />
      <DialogFooter>
        <Button variant="secondary" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        <Button onClick={() => void submit()} loading={busy}>
          {attachment.progress !== null && attachment.progress < 100
            ? `Uploading ${attachment.progress}%`
            : 'Send'}
        </Button>
      </DialogFooter>
    </>
  )
}
