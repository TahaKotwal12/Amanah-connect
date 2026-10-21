import { useEffect, useMemo, useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { todayInIst } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Combobox } from '@/shared/components/ui/combobox'
import { DatePicker } from '@/shared/components/ui/date-picker'
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
import type { Uuid } from '@/types'
import { useCommunities } from '../hooks/useCommunities'
import { usePlans } from '../hooks/usePlans'
import { useRecordPayment } from '../hooks/useSubscriptions'
import {
  defaultPeriodEnd,
  recordPaymentSchema,
  type RecordPaymentInput,
  type RecordPaymentValues,
} from '../paymentSchema'

interface RecordPaymentSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Pre-select a community, for example from its own page. */
  communityId?: Uuid
}

const FIELDS = [
  'communityId',
  'planId',
  'amount',
  'reference',
  'periodStart',
  'periodEnd',
  'paidOn',
]
/** The communities picker lists up to this many, searchable in the box. */
const PICKER_SIZE = 100

function emptyForm(communityId?: Uuid): RecordPaymentInput {
  const start = todayInIst()
  return {
    communityId: communityId ?? '',
    planId: '',
    amount: '',
    reference: '',
    periodStart: start,
    periodEnd: defaultPeriodEnd(start),
    paidOn: start,
  }
}

/** Records a payment that arrived outside the app (bank transfer, UPI): it extends the plan period. */
export function RecordPaymentSheet({ open, onOpenChange, communityId }: RecordPaymentSheetProps) {
  const record = useRecordPayment()
  const plans = usePlans()
  const communities = useCommunities({ size: PICKER_SIZE, sort: ['name,asc'] }, { enabled: open })
  const [formError, setFormError] = useState<string | null>(null)
  const form = useZodForm(recordPaymentSchema, { defaultValues: emptyForm(communityId) })

  function close(next: boolean) {
    if (!next) setFormError(null)
    onOpenChange(next)
  }

  useEffect(() => {
    if (open) {
      form.reset(emptyForm(communityId))
    }
  }, [open, communityId, form])

  const planOptions = useMemo(
    () =>
      (plans.data ?? [])
        .filter((plan) => plan.active)
        .map((plan) => ({ value: plan.id, label: `${plan.name} (${plan.code})` })),
    [plans.data],
  )
  const communityOptions = useMemo(
    () =>
      (communities.data?.items ?? []).map((community) => ({
        value: community.id,
        label: community.name,
      })),
    [communities.data],
  )

  async function submit(values: RecordPaymentValues) {
    setFormError(null)
    try {
      await record.mutateAsync({
        communityId: values.communityId,
        planId: values.planId,
        amount: values.amount,
        periodStart: values.periodStart,
        periodEnd: values.periodEnd,
        ...(values.reference ? { reference: values.reference } : {}),
        ...(values.paidOn ? { paidOn: values.paidOn } : {}),
      })
      notify.success('Payment recorded')
      onOpenChange(false)
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <Sheet open={open} onOpenChange={(next) => !record.isPending && close(next)}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Record a payment</SheetTitle>
          <SheetDescription>
            For money received outside the app. It starts a new subscription period for the
            community; this cannot be edited afterwards, only cancelled with a reason.
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

            <FormField control={form.control} name="communityId" label="Community" required>
              {({ field, control }) => (
                <Combobox
                  id={control.id}
                  aria-invalid={control['aria-invalid']}
                  aria-describedby={control['aria-describedby']}
                  options={communityOptions}
                  value={field.value}
                  onValueChange={field.onChange}
                  onBlur={field.onBlur}
                  placeholder={communities.isLoading ? 'Loading communities' : 'Choose a community'}
                  searchPlaceholder="Search communities"
                  disabled={communityId !== undefined}
                />
              )}
            </FormField>

            <FormField control={form.control} name="planId" label="Plan" required>
              {({ field, control }) => (
                <Combobox
                  id={control.id}
                  aria-invalid={control['aria-invalid']}
                  aria-describedby={control['aria-describedby']}
                  options={planOptions}
                  value={field.value}
                  onValueChange={(value) => {
                    field.onChange(value)
                    // Suggest the yearly price, but never overwrite what was typed.
                    if (!form.getFieldState('amount').isDirty) {
                      const plan = plans.data?.find((candidate) => candidate.id === value)
                      if (plan) form.setValue('amount', plan.priceYearly)
                    }
                  }}
                  onBlur={field.onBlur}
                  placeholder="Choose a plan"
                  searchPlaceholder="Search plans"
                />
              )}
            </FormField>

            <FormField control={form.control} name="amount" label="Amount received" required>
              {({ field, control }) => (
                <MoneyInput
                  id={control.id}
                  name={control.name}
                  aria-invalid={control['aria-invalid']}
                  aria-describedby={control['aria-describedby']}
                  aria-required={control['aria-required']}
                  value={field.value}
                  onValueChange={(value) => field.onChange(value)}
                  onBlur={field.onBlur}
                  symbol="₹"
                />
              )}
            </FormField>

            <div className="grid gap-4 sm:grid-cols-2">
              <FormField control={form.control} name="periodStart" label="Period starts" required>
                {({ field, control }) => (
                  <DatePicker
                    id={control.id}
                    aria-invalid={control['aria-invalid']}
                    aria-describedby={control['aria-describedby']}
                    value={field.value || undefined}
                    onValueChange={(value) => {
                      field.onChange(value ?? '')
                      if (value && !form.getFieldState('periodEnd').isDirty) {
                        form.setValue('periodEnd', defaultPeriodEnd(value))
                      }
                    }}
                    onBlur={field.onBlur}
                  />
                )}
              </FormField>
              <FormField control={form.control} name="periodEnd" label="Period ends" required>
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
            </div>

            <FormField
              control={form.control}
              name="paidOn"
              label="Received on"
              description="Defaults to today."
            >
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

            <FormField
              control={form.control}
              name="reference"
              label="Reference"
              description="Bank or UPI transaction number, for your records."
            >
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>

            <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
              <Button
                type="button"
                variant="secondary"
                onClick={() => close(false)}
                disabled={record.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" loading={record.isPending}>
                Record payment
              </Button>
            </div>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
