import { useEffect, useState } from 'react'
import { useWatch } from 'react-hook-form'
import { isApiError, toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import { MoneyInput } from '@/shared/components/ui/money-input'
import { SimpleSelect } from '@/shared/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import {
  FEE_KINDS,
  emptyInvoice,
  newInvoiceSchema,
  toCreateInvoiceRequest,
  type NewInvoiceInput,
  type NewInvoiceValues,
} from '../billingSchemas'
import { useCreateInvoice } from '../hooks/useBilling'
import { MemberPicker } from './MemberPicker'

interface NewInvoiceSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

const FIELDS = ['memberId', 'kind', 'description', 'amount', 'dueDate', 'period']

/** A bill for one member, outside the regular fee plans (a fine, an event fee). */
export function NewInvoiceSheet({ open, onOpenChange }: NewInvoiceSheetProps) {
  const create = useCreateInvoice()
  const form = useZodForm(newInvoiceSchema, { defaultValues: emptyInvoice() })
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    if (open) form.reset(emptyInvoice())
  }, [open, form])

  const watched = useWatch({ control: form.control }) as NewInvoiceInput

  function close(next: boolean) {
    if (create.isPending) return
    if (!next) setFormError(null)
    onOpenChange(next)
  }

  async function submit(values: NewInvoiceValues) {
    setFormError(null)
    try {
      const invoice = await create.mutateAsync(toCreateInvoiceRequest(values))
      notify.success(`${invoice.invoiceNo} created`)
      setFormError(null)
      // Not close(): the mutation is still pending in this render, and close() ignores that.
      onOpenChange(false)
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
          <SheetTitle>New invoice</SheetTitle>
          <SheetDescription>
            A bill for one member. Regular fees are billed to everyone at once from a fee plan.
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
            <FormField control={form.control} name="memberId" label="Member" required>
              {({ field, control }) => (
                <MemberPicker
                  id={control.id}
                  aria-invalid={control['aria-invalid']}
                  aria-describedby={control['aria-describedby']}
                  value={field.value}
                  onChange={field.onChange}
                />
              )}
            </FormField>
            <FormField control={form.control} name="kind" label="Kind" required>
              {({ field, control }) => (
                <SimpleSelect
                  id={control.id}
                  options={FEE_KINDS}
                  value={field.value}
                  onValueChange={field.onChange}
                />
              )}
            </FormField>
            <FormField control={form.control} name="description" label="What it is for" required>
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <FormField control={form.control} name="amount" label="Amount" required>
              {({ field, control }) => (
                <MoneyInput
                  id={control.id}
                  name={control.name}
                  aria-invalid={control['aria-invalid']}
                  aria-describedby={control['aria-describedby']}
                  aria-required={control['aria-required']}
                  value={field.value}
                  onValueChange={field.onChange}
                  onBlur={field.onBlur}
                  symbol="₹"
                />
              )}
            </FormField>
            <FormField control={form.control} name="dueDate" label="Due date" required>
              {({ field, control }) => (
                <DatePicker
                  id={control.id}
                  aria-invalid={control['aria-invalid']}
                  aria-describedby={control['aria-describedby']}
                  value={field.value || undefined}
                  onValueChange={(value) => field.onChange(value ?? '')}
                  onBlur={field.onBlur}
                />
              )}
            </FormField>
            <FormField
              control={form.control}
              name="period"
              label="Period"
              description="Optional, for example 2026-10."
            >
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <Checkbox
              label="Email the bill to the member"
              checked={watched.sendEmail === true}
              onCheckedChange={(checked) =>
                form.setValue('sendEmail', checked === true, { shouldDirty: true })
              }
            />
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
                Create invoice
              </Button>
            </div>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
