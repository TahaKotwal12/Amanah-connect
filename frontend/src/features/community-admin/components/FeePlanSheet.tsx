import { useEffect, useState } from 'react'
import { useWatch } from 'react-hook-form'
import { toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import { MoneyInput } from '@/shared/components/ui/money-input'
import { RadioGroup, RadioGroupItem } from '@/shared/components/ui/radio-group'
import { SimpleSelect } from '@/shared/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import { Switch } from '@/shared/components/ui/switch'
import type { FeePlanView } from '@/types'
import {
  EMPTY_FEE_PLAN,
  FEE_AUDIENCES,
  FEE_FREQUENCIES,
  FEE_KINDS,
  feePlanSchema,
  feePlanToForm,
  toCreateFeePlanRequest,
  toUpdateFeePlanRequest,
  type FeePlanInput,
  type FeePlanValues,
} from '../billingSchemas'
import { useCreateFeePlan, useUpdateFeePlan } from '../hooks/useBilling'
import { MemberMultiPicker } from './MemberPicker'

interface FeePlanSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The plan being edited; undefined creates a new one. */
  plan?: FeePlanView | undefined
}

const FIELDS = ['name', 'kind', 'amount', 'frequency', 'dueDay', 'appliesTo', 'group', 'memberIds']

/** Create or edit a fee plan: what is charged, how often, and to whom. */
export function FeePlanSheet({ open, onOpenChange, plan }: FeePlanSheetProps) {
  const create = useCreateFeePlan()
  const update = useUpdateFeePlan()
  const form = useZodForm(feePlanSchema, {
    defaultValues: plan ? feePlanToForm(plan) : EMPTY_FEE_PLAN,
  })
  const [formError, setFormError] = useState<string | null>(null)
  const editing = plan !== undefined

  useEffect(() => {
    if (open) form.reset(plan ? feePlanToForm(plan) : EMPTY_FEE_PLAN)
  }, [open, plan, form])

  const watched = useWatch({ control: form.control }) as FeePlanInput
  const busy = create.isPending || update.isPending

  function close(next: boolean) {
    if (busy) return
    if (!next) setFormError(null)
    onOpenChange(next)
  }

  async function submit(values: FeePlanValues) {
    setFormError(null)
    try {
      if (plan) {
        await update.mutateAsync({ id: plan.id, body: toUpdateFeePlanRequest(values) })
        notify.success(`${values.name} saved`)
      } else {
        await create.mutateAsync(toCreateFeePlanRequest(values))
        notify.success(`${values.name} created`)
      }
      setFormError(null)
      // Not close(): the mutation is still pending in this render, and close() ignores that.
      onOpenChange(false)
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <Sheet open={open} onOpenChange={close}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>{plan ? `Edit ${plan.name}` : 'New fee plan'}</SheetTitle>
          <SheetDescription>
            {plan
              ? 'A new amount applies to future invoices only. The kind and how often it repeats cannot change.'
              : 'What members are charged, how often, and who is charged.'}
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
            <FormField control={form.control} name="name" label="Name" required>
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField control={form.control} name="kind" label="Kind" required>
                {({ field, control }) => (
                  <SimpleSelect
                    id={control.id}
                    options={FEE_KINDS}
                    value={field.value}
                    onValueChange={field.onChange}
                    disabled={editing}
                  />
                )}
              </FormField>
              <FormField control={form.control} name="frequency" label="How often" required>
                {({ field, control }) => (
                  <SimpleSelect
                    id={control.id}
                    options={FEE_FREQUENCIES}
                    value={field.value}
                    onValueChange={field.onChange}
                    disabled={editing}
                  />
                )}
              </FormField>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField control={form.control} name="amount" label="Amount each" required>
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
              <FormField
                control={form.control}
                name="dueDay"
                label="Due on day"
                description="Of the month, 1 to 28."
              >
                {({ field, control }) => <Input {...field} {...control} inputMode="numeric" />}
              </FormField>
            </div>

            <fieldset className="grid gap-3 rounded border border-brand-100 p-4">
              <legend className="px-1 text-sm font-semibold text-brand-900">Who is charged</legend>
              <FormField
                control={form.control}
                name="appliesTo"
                label="Who is charged"
                hideLabel
                asGroup
              >
                {({ field }) => (
                  <RadioGroup
                    value={field.value}
                    onValueChange={field.onChange}
                    aria-label="Who is charged"
                  >
                    {FEE_AUDIENCES.map((option) => (
                      <RadioGroupItem
                        key={option.value}
                        value={option.value}
                        label={option.label}
                      />
                    ))}
                  </RadioGroup>
                )}
              </FormField>
              {watched.appliesTo === 'GROUP' ? (
                <FormField control={form.control} name="group" label="Group" required>
                  {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
                </FormField>
              ) : null}
              {watched.appliesTo === 'SELECTED' ? (
                <FormField control={form.control} name="memberIds" label="Members" required>
                  {({ field, control }) => (
                    <MemberMultiPicker
                      id={control.id}
                      aria-invalid={control['aria-invalid']}
                      aria-describedby={control['aria-describedby']}
                      value={field.value}
                      onChange={field.onChange}
                    />
                  )}
                </FormField>
              ) : null}
            </fieldset>

            <div className="grid gap-3">
              <Switch
                label="Bill automatically every period"
                checked={watched.autoGenerate === true}
                onCheckedChange={(checked) =>
                  form.setValue('autoGenerate', checked, {
                    shouldDirty: true,
                    shouldValidate: true,
                  })
                }
              />
              {form.formState.errors.autoGenerate?.message ? (
                <p role="alert" className="text-sm text-danger">
                  {form.formState.errors.autoGenerate.message}
                </p>
              ) : null}
              {editing ? (
                <Checkbox
                  label="This plan is in use"
                  description="Untick to retire it. Plans are never deleted, and past invoices stay."
                  checked={watched.active === true}
                  onCheckedChange={(checked) =>
                    form.setValue('active', checked === true, { shouldDirty: true })
                  }
                />
              ) : null}
            </div>

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
                {plan ? 'Save changes' : 'Create fee plan'}
              </Button>
            </div>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
