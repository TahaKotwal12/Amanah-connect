import { useEffect, useState } from 'react'
import { useWatch } from 'react-hook-form'
import { PlanCard } from '@/features/landing/sections/Pricing'
import { toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import { MoneyInput } from '@/shared/components/ui/money-input'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import { Switch } from '@/shared/components/ui/switch'
import type { PlanView } from '@/types'
import { useCreatePlan, useUpdatePlan } from '../hooks/usePlans'
import {
  EMPTY_PLAN_FORM,
  FEATURE_FIELDS,
  LIMIT_FIELDS,
  formToLimits,
  formToPublicPlan,
  planSchema,
  planToForm,
  type PlanFormInput,
  type PlanFormValues,
} from '../planDefinition'

interface PlanEditorSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The plan being edited; undefined creates a new one. */
  plan?: PlanView | undefined
}

/** Server field names such as "limits.max_members" land on the matching input. */
const KNOWN_ERROR_FIELDS: string[] = [
  'code',
  'name',
  'priceMonthly',
  'priceYearly',
  'sortOrder',
  ...LIMIT_FIELDS.map(({ key }) => `limits.${key}`),
]

export function PlanEditorSheet({ open, onOpenChange, plan }: PlanEditorSheetProps) {
  const create = useCreatePlan()
  const update = useUpdatePlan()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useZodForm(planSchema, {
    defaultValues: plan ? planToForm(plan) : EMPTY_PLAN_FORM,
  })

  // A different plan (or a fresh "new plan") gets a clean form each time the sheet opens.
  function close(next: boolean) {
    if (!next) setFormError(null)
    onOpenChange(next)
  }

  useEffect(() => {
    if (open) {
      form.reset(plan ? planToForm(plan) : EMPTY_PLAN_FORM)
    }
  }, [open, plan, form])

  const watched = useWatch({ control: form.control }) as PlanFormInput
  const busy = create.isPending || update.isPending

  async function submit(values: PlanFormValues) {
    setFormError(null)
    const body = {
      name: values.name,
      priceMonthly: values.priceMonthly,
      priceYearly: values.priceYearly,
      limits: formToLimits(values.limits),
      features: { ...values.features },
      publicPlan: values.publicPlan,
      active: values.active,
      sortOrder: Number(values.sortOrder),
    }
    try {
      if (plan) {
        await update.mutateAsync({ id: plan.id, body })
        notify.success(`${values.name} saved`)
      } else {
        await create.mutateAsync({ code: values.code, ...body })
        notify.success(`${values.name} created`)
      }
      onOpenChange(false)
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, KNOWN_ERROR_FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <Sheet open={open} onOpenChange={(next) => !busy && close(next)}>
      <SheetContent className="max-w-xl">
        <SheetHeader>
          <SheetTitle>{plan ? `Edit ${plan.name}` : 'New plan'}</SheetTitle>
          <SheetDescription>
            Prices and limits apply to new subscriptions and to what the website shows. A community
            already on this plan keeps its current period.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form
            noValidate
            onSubmit={(event) => {
              void form.handleSubmit(submit)(event)
            }}
            className="grid gap-5"
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
                name="code"
                label="Code"
                required
                description={plan ? 'The code never changes.' : 'Upper case, for example GROWTH.'}
              >
                {({ field, control }) => (
                  <Input {...field} {...control} disabled={plan !== undefined} autoComplete="off" />
                )}
              </FormField>
              <FormField control={form.control} name="name" label="Name" required>
                {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
              </FormField>
              <FormField
                control={form.control}
                name="priceMonthly"
                label="Price per month"
                required
              >
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
              <FormField control={form.control} name="priceYearly" label="Price per year" required>
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
            </div>

            <fieldset className="grid gap-4 rounded border border-brand-100 p-4">
              <legend className="px-1 text-sm font-semibold text-brand-900">Limits</legend>
              <p className="-mt-2 text-sm text-muted">Leave a limit empty for unlimited.</p>
              <div className="grid gap-4 sm:grid-cols-2">
                {LIMIT_FIELDS.map(({ key, label, unit }) => (
                  <FormField
                    key={key}
                    control={form.control}
                    name={`limits.${key}` as const}
                    label={label}
                    description={`In ${unit}`}
                  >
                    {({ field, control }) => (
                      <Input {...field} {...control} inputMode="numeric" placeholder="Unlimited" />
                    )}
                  </FormField>
                ))}
              </div>
            </fieldset>

            <fieldset className="grid gap-1 rounded border border-brand-100 p-4">
              <legend className="px-1 text-sm font-semibold text-brand-900">Features</legend>
              {FEATURE_FIELDS.map(({ key, label }) => (
                <Checkbox
                  key={key}
                  label={label}
                  checked={watched.features?.[key] === true}
                  onCheckedChange={(checked) =>
                    form.setValue(`features.${key}`, checked === true, { shouldDirty: true })
                  }
                />
              ))}
            </fieldset>

            <div className="grid gap-4 sm:grid-cols-2">
              <FormField
                control={form.control}
                name="sortOrder"
                label="Display order"
                description="Lower numbers come first."
              >
                {({ field, control }) => <Input {...field} {...control} inputMode="numeric" />}
              </FormField>
              <div className="grid content-start gap-3">
                <Switch
                  label="Show on the website"
                  checked={watched.publicPlan === true}
                  onCheckedChange={(checked) =>
                    form.setValue('publicPlan', checked, { shouldDirty: true })
                  }
                />
                <Switch
                  label="Available to assign"
                  checked={watched.active === true}
                  onCheckedChange={(checked) =>
                    form.setValue('active', checked, { shouldDirty: true })
                  }
                />
              </div>
            </div>

            <section aria-labelledby="plan-preview-title" className="grid gap-2">
              <h3 id="plan-preview-title" className="text-sm font-semibold text-brand-900">
                How it looks on the website
              </h3>
              {watched.publicPlan ? null : (
                <p className="text-sm text-muted">
                  This plan is not shown on the website. This is how it would look if it were.
                </p>
              )}
              <PlanCard plan={formToPublicPlan(watched)} billing="monthly" highlighted={false} />
            </section>

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
                {plan ? 'Save changes' : 'Create plan'}
              </Button>
            </div>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
