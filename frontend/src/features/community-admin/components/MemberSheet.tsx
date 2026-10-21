import { useEffect, useState } from 'react'
import { useWatch } from 'react-hook-form'
import { isApiError, toApiError } from '@/lib/apiError'
import { todayInIst } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import type { MemberView } from '@/types'
import {
  EMPTY_MEMBER,
  memberSchema,
  memberToForm,
  toCreateMemberRequest,
  toUpdateMemberRequest,
  type MemberInput,
  type MemberValues,
} from '../memberSchema'
import { useCreateMember, useUpdateMember } from '../hooks/useMembers'
import { useSettings } from '../hooks/useSettings'

interface MemberSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The member being edited; undefined adds a new one. */
  member?: MemberView | undefined
  /** Called with the saved member, so a list can move to it. */
  onSaved?: (member: MemberView) => void
}

const FIELDS = ['fullName', 'email', 'phone', 'group', 'joinedOn', 'consentEmail']

/** Add or edit a member in a panel beside the list. */
export function MemberSheet({ open, onOpenChange, member, onSaved }: MemberSheetProps) {
  const create = useCreateMember()
  const update = useUpdateMember()
  const settings = useSettings()
  const groupLabel = settings.data?.memberGroupLabel ?? 'Group'
  const [formError, setFormError] = useState<string | null>(null)
  /** The server said another member already has this address. */
  const [duplicate, setDuplicate] = useState(false)
  const original = member ? memberToForm(member) : EMPTY_MEMBER
  const form = useZodForm(memberSchema, { defaultValues: original })

  useEffect(() => {
    if (open) form.reset(member ? memberToForm(member) : EMPTY_MEMBER)
  }, [open, member, form])

  const watched = useWatch({ control: form.control }) as MemberInput
  const busy = create.isPending || update.isPending

  function close(next: boolean) {
    if (busy) return
    if (!next) {
      setFormError(null)
      setDuplicate(false)
    }
    onOpenChange(next)
  }

  async function submit(values: MemberValues) {
    setFormError(null)
    try {
      let saved: MemberView
      if (member) {
        const body = toUpdateMemberRequest(values, memberToForm(member))
        if (Object.keys(body).length === 0) {
          notify.info('Nothing to save: no field was changed')
          return
        }
        saved = await update.mutateAsync({ id: member.id, body })
      } else {
        saved = await create.mutateAsync(toCreateMemberRequest(values))
      }
      notify.success(member ? 'Member saved' : `${saved.fullName} added as ${saved.memberNo}`)
      onSaved?.(saved)
      // Not close(): the mutation is still "pending" in this render, and close() ignores that.
      setFormError(null)
      setDuplicate(false)
      onOpenChange(false)
    } catch (error) {
      if (isApiError(error) && error.code === 'DUPLICATE_EMAIL') {
        setDuplicate(true)
        return
      }
      // A plan limit gets its own dialog (PlanLimitDialog); nothing more to show here.
      if (isApiError(error) && error.code === 'PLAN_LIMIT_EXCEEDED') {
        onOpenChange(false)
        return
      }
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <Sheet open={open} onOpenChange={close}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>{member ? `Edit ${member.fullName}` : 'Add a member'}</SheetTitle>
          <SheetDescription>
            {member
              ? `Member number ${member.memberNo}. It never changes.`
              : 'The member number is made for you.'}
          </SheetDescription>
        </SheetHeader>

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

            <FormField control={form.control} name="fullName" label="Full name" required>
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <FormField control={form.control} name="email" label="Email">
              {({ field, control }) => (
                <Input
                  {...field}
                  {...control}
                  type="email"
                  autoComplete="off"
                  onChange={(event) => {
                    field.onChange(event)
                    setDuplicate(false)
                    form.setValue('allowDuplicateEmail', false)
                  }}
                />
              )}
            </FormField>
            {duplicate ? (
              <div
                role="alert"
                className="grid gap-2 rounded border border-warning/40 bg-warning-50 p-3 text-sm"
              >
                <p>
                  Another member already uses this address. Is it shared, for example by a
                  household?
                </p>
                <Checkbox
                  label="Yes, these members share this address"
                  checked={watched.allowDuplicateEmail === true}
                  onCheckedChange={(checked) =>
                    form.setValue('allowDuplicateEmail', checked === true, { shouldDirty: true })
                  }
                />
              </div>
            ) : null}
            <FormField control={form.control} name="phone" label="Phone">
              {({ field, control }) => (
                <Input {...field} {...control} type="tel" autoComplete="off" />
              )}
            </FormField>
            <FormField control={form.control} name="group" label={groupLabel}>
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <FormField control={form.control} name="joinedOn" label="Joined on">
              {({ field, control }) => (
                <DatePicker
                  id={control.id}
                  aria-invalid={control['aria-invalid']}
                  aria-describedby={control['aria-describedby']}
                  value={field.value || undefined}
                  onValueChange={(value) => field.onChange(value ?? '')}
                  onBlur={field.onBlur}
                  max={todayInIst()}
                />
              )}
            </FormField>
            <Checkbox
              label="Agrees to receive emails"
              description="Bills, receipts, reminders and announcements go to members who agree."
              checked={watched.consentEmail === true}
              onCheckedChange={(checked) =>
                form.setValue('consentEmail', checked === true, { shouldDirty: true })
              }
            />

            <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
              <Button
                type="button"
                variant="secondary"
                onClick={() => close(false)}
                disabled={busy}
              >
                Cancel
              </Button>
              <Button type="submit" loading={busy}>
                {member ? 'Save changes' : 'Add member'}
              </Button>
            </div>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
