import { useEffect, useState } from 'react'
import { useWatch } from 'react-hook-form'
import { isApiError, toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input, Textarea } from '@/shared/components/ui/input'
import { SimpleSelect } from '@/shared/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import type { Uuid } from '@/types'
import {
  PRIORITIES,
  emptyComplaint,
  newComplaintSchema,
  toCreateComplaintRequest,
  type NewComplaintInput,
  type NewComplaintValues,
} from '../complaintSchemas'
import { useAssignees, useCreateComplaint } from '../hooks/useComplaints'
import { MemberPicker } from './MemberPicker'

const FIELDS = ['memberId', 'subject', 'description', 'priority', 'category', 'assignedTo']
const NOBODY = 'none'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Start the form on this member (from a member's page). */
  memberId?: Uuid
  /** Called with the new complaint's id instead of onOpenChange(false): the page closes this and opens it. */
  onCreated?: (id: Uuid) => void
}

/** Log a complaint on behalf of a member (or about the community in general). */
export function NewComplaintSheet({ open, onOpenChange, memberId, onCreated }: Props) {
  const create = useCreateComplaint()
  const assignees = useAssignees()
  const form = useZodForm(newComplaintSchema, { defaultValues: emptyComplaint(memberId) })
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    if (open) form.reset(emptyComplaint(memberId))
  }, [open, memberId, form])

  const watched = useWatch({ control: form.control }) as NewComplaintInput

  function close(next: boolean) {
    if (create.isPending) return
    if (!next) setFormError(null)
    onOpenChange(next)
  }

  async function submit(values: NewComplaintValues) {
    setFormError(null)
    try {
      const made = await create.mutateAsync(toCreateComplaintRequest(values))
      notify.success('Complaint logged')
      // One URL update only: two in a row would each start from the same old address.
      if (onCreated) onCreated(made.id)
      // Not close(): the mutation is still pending in this render, and close() ignores that.
      else onOpenChange(false)
    } catch (error) {
      if (isApiError(error) && error.code === 'PLAN_LIMIT_EXCEEDED') return
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <Sheet open={open} onOpenChange={close}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Log a complaint</SheetTitle>
          <SheetDescription>
            Record a complaint on behalf of a member, or about the community in general.
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
            <FormField
              control={form.control}
              name="memberId"
              label="On behalf of"
              description="Leave empty if it is not about one member."
            >
              {({ field, control }) => (
                <div className="grid gap-1">
                  <MemberPicker
                    id={control.id}
                    aria-invalid={control['aria-invalid']}
                    aria-describedby={control['aria-describedby']}
                    value={field.value}
                    onChange={field.onChange}
                  />
                  {watched.memberId ? (
                    <button
                      type="button"
                      className="justify-self-start text-sm underline"
                      onClick={() => field.onChange('')}
                    >
                      Not about a member
                    </button>
                  ) : null}
                </div>
              )}
            </FormField>
            <FormField control={form.control} name="subject" label="Subject" required>
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <FormField control={form.control} name="description" label="What happened" required>
              {({ field, control }) => <Textarea {...field} {...control} rows={5} />}
            </FormField>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField control={form.control} name="priority" label="Priority" required>
                {({ field, control }) => (
                  <SimpleSelect
                    id={control.id}
                    options={PRIORITIES}
                    value={field.value}
                    onValueChange={field.onChange}
                  />
                )}
              </FormField>
              <FormField
                control={form.control}
                name="category"
                label="Category"
                description="For example Water or Parking."
              >
                {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
              </FormField>
            </div>
            <FormField control={form.control} name="assignedTo" label="Assign to">
              {({ field, control }) => (
                <SimpleSelect
                  id={control.id}
                  options={[
                    { value: NOBODY, label: 'Nobody yet' },
                    ...(assignees.data ?? []).map((a) => ({ value: a.id, label: a.fullName })),
                  ]}
                  value={field.value || NOBODY}
                  onValueChange={(value) => field.onChange(value === NOBODY ? '' : value)}
                />
              )}
            </FormField>
            <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
              <Button
                type="button"
                variant="secondary"
                onClick={() => close(false)}
                disabled={create.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" loading={create.isPending}>
                Log complaint
              </Button>
            </div>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
