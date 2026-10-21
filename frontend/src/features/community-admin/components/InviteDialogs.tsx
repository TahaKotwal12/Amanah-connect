import { Download, Printer } from 'lucide-react'
import { useEffect, useState } from 'react'
import { isApiError, toApiError } from '@/lib/apiError'
import { formatDate } from '@/lib/dates'
import { downloadBlob } from '@/lib/download'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { CopyField } from '@/shared/components/ui/copy-field'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import type { CreatedInviteView } from '@/types'
import {
  DEFAULT_EMAIL_INVITE,
  DEFAULT_INVITE,
  createInviteSchema,
  inviteByEmailSchema,
  toCreateInviteRequest,
  toInviteByEmailRequest,
  type CreateInviteValues,
  type InviteByEmailValues,
} from '../inviteSchema'
import { useCreateInvite, useInviteByEmail } from '../hooks/useInvites'
import { useSettings } from '../hooks/useSettings'

/** A PNG from the base64 the server sends. */
function pngBlob(base64: string): Blob {
  const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0))
  return new Blob([bytes], { type: 'image/png' })
}

/**
 * The invite as a card to print and put on a notice board: community name and logo, what to do,
 * the QR code and the link. Printing shows only this card (see the print rules in index.css).
 */
function PrintableCard({ created, groupText }: { created: CreatedInviteView; groupText: string }) {
  const settings = useSettings()
  const name = settings.data?.name ?? 'Our community'
  const logo = settings.data?.logoUrl
  return (
    <div
      data-print-card
      className="mx-auto grid w-full max-w-sm justify-items-center gap-3 rounded-card border-2 border-brand-900 bg-white p-6 text-center text-ink"
    >
      {logo ? <img src={logo} alt="" className="h-16 w-auto object-contain" /> : null}
      <p className="font-heading text-2xl font-semibold text-brand-900">{name}</p>
      <p className="text-lg font-medium">Join our community</p>
      <p className="text-sm">Scan with your phone camera and fill in the short form.</p>
      <img
        src={`data:image/png;base64,${created.qrCodePngBase64}`}
        alt={`QR code to register with ${name}`}
        className="size-56"
      />
      <p className="break-all text-xs">{created.link}</p>
      <p className="text-xs text-muted">
        {groupText}Valid until {formatDate(created.invite.expiresAt)}. A committee member approves
        each registration.
      </p>
    </div>
  )
}

interface CreateInviteDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/** Make a link anyone can use to register. The link and its QR code are shown once, here. */
export function CreateInviteDialog({ open, onOpenChange }: CreateInviteDialogProps) {
  const create = useCreateInvite()
  const form = useZodForm(createInviteSchema, { defaultValues: DEFAULT_INVITE })
  const [created, setCreated] = useState<CreatedInviteView | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    if (open) form.reset(DEFAULT_INVITE)
  }, [open, form])

  function close(next: boolean) {
    if (create.isPending) return
    if (!next) {
      setCreated(null)
      setFormError(null)
    }
    onOpenChange(next)
  }

  async function submit(values: CreateInviteValues) {
    setFormError(null)
    try {
      setCreated(await create.mutateAsync(toCreateInviteRequest(values)))
    } catch (error) {
      if (isApiError(error) && error.code === 'PLAN_LIMIT_EXCEEDED') return
      const unmatched = applyApiFieldErrors(form, error, [
        'expiresInDays',
        'maxUses',
        'defaultGroup',
      ])
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="max-h-[92vh] max-w-lg overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{created ? 'Your invite link' : 'Create an invite link'}</DialogTitle>
          <DialogDescription>
            {created
              ? 'Copy the link or print the card. For safety this is the only time the link is shown.'
              : 'Anyone with the link can ask to join. You approve each registration before they become a member.'}
          </DialogDescription>
        </DialogHeader>

        {created ? (
          <div className="grid gap-4">
            <CopyField label="Invite link" value={created.link} />
            <PrintableCard
              created={created}
              groupText={
                created.invite.defaultGroup ? `Group: ${created.invite.defaultGroup}. ` : ''
              }
            />
            <div className="flex flex-wrap justify-end gap-2">
              <Button
                variant="secondary"
                onClick={() => downloadBlob('invite-qr.png', pngBlob(created.qrCodePngBase64))}
              >
                <Download aria-hidden="true" className="size-4" />
                Download QR code (PNG)
              </Button>
              <Button variant="secondary" onClick={() => window.print()}>
                <Printer aria-hidden="true" className="size-4" />
                Print the card
              </Button>
              <Button onClick={() => close(false)}>Done</Button>
            </div>
          </div>
        ) : (
          <Form {...form}>
            <form
              noValidate
              onSubmit={(event) => {
                void form.handleSubmit(submit)(event)
              }}
              className="grid gap-4"
            >
              {formError ? (
                <p
                  role="alert"
                  className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
                >
                  {formError}
                </p>
              ) : null}
              <div className="grid gap-4 sm:grid-cols-2">
                <FormField
                  control={form.control}
                  name="expiresInDays"
                  label="Valid for (days)"
                  description="1 to 90"
                  required
                >
                  {({ field, control }) => <Input {...field} {...control} inputMode="numeric" />}
                </FormField>
                <FormField
                  control={form.control}
                  name="maxUses"
                  label="Most people"
                  description="1 to 1,000"
                  required
                >
                  {({ field, control }) => <Input {...field} {...control} inputMode="numeric" />}
                </FormField>
              </div>
              <FormField
                control={form.control}
                name="defaultGroup"
                label="Put new members in this group"
                description="Optional, for example a block or a team."
              >
                {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
              </FormField>
              <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
                <Button type="button" variant="secondary" onClick={() => close(false)}>
                  Cancel
                </Button>
                <Button type="submit" loading={create.isPending}>
                  Create link
                </Button>
              </div>
            </form>
          </Form>
        )}
      </DialogContent>
    </Dialog>
  )
}

/** Send one person a personal invitation by email. */
export function InviteByEmailDialog({ open, onOpenChange }: CreateInviteDialogProps) {
  const invite = useInviteByEmail()
  const form = useZodForm(inviteByEmailSchema, { defaultValues: DEFAULT_EMAIL_INVITE })
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    if (open) form.reset(DEFAULT_EMAIL_INVITE)
  }, [open, form])

  function close(next: boolean) {
    if (invite.isPending) return
    if (!next) setFormError(null)
    onOpenChange(next)
  }

  async function submit(values: InviteByEmailValues) {
    setFormError(null)
    try {
      await invite.mutateAsync(toInviteByEmailRequest(values))
      notify.success(`Invitation sent to ${values.email}`)
      // Not close(): the mutation is still pending in this render, and close() ignores that.
      onOpenChange(false)
    } catch (error) {
      if (isApiError(error) && error.code === 'PLAN_LIMIT_EXCEEDED') {
        onOpenChange(false)
        return
      }
      const unmatched = applyApiFieldErrors(form, error, [
        'email',
        'name',
        'expiresInDays',
        'defaultGroup',
      ])
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Invite someone by email</DialogTitle>
          <DialogDescription>
            They get a personal link that works once. You still approve their registration.
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form
            noValidate
            onSubmit={(event) => {
              void form.handleSubmit(submit)(event)
            }}
            className="grid gap-4"
          >
            {formError ? (
              <p
                role="alert"
                className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
              >
                {formError}
              </p>
            ) : null}
            <FormField control={form.control} name="email" label="Email" required>
              {({ field, control }) => (
                <Input {...field} {...control} type="email" autoComplete="off" />
              )}
            </FormField>
            <FormField control={form.control} name="name" label="Their name">
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField
                control={form.control}
                name="expiresInDays"
                label="Valid for (days)"
                description="1 to 90"
                required
              >
                {({ field, control }) => <Input {...field} {...control} inputMode="numeric" />}
              </FormField>
              <FormField control={form.control} name="defaultGroup" label="Group">
                {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
              </FormField>
            </div>
            <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
              <Button type="button" variant="secondary" onClick={() => close(false)}>
                Cancel
              </Button>
              <Button type="submit" loading={invite.isPending}>
                Send invitation
              </Button>
            </div>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
