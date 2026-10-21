import { useEffect, useMemo, useState } from 'react'
import { useWatch, type Control } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { toApiError } from '@/lib/apiError'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
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
import { RadioGroup, RadioGroupItem } from '@/shared/components/ui/radio-group'
import { SimpleSelect } from '@/shared/components/ui/select'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { Stepper } from '@/shared/components/ui/stepper'
import type { CreateCommunityRequest, PlanView, Uuid } from '@/types'
import {
  CREATE_STEP_FIELDS,
  EMPTY_CREATE,
  createCommunitySchema,
  draftToForm,
  toCreateRequest,
  type CreateCommunityInput,
  type CreateCommunityValues,
} from '../communitySchema'
import { useCreateCommunity } from '../hooks/useCommunities'
import { usePlans } from '../hooks/usePlans'
import { ProfileFields, type ProfileInput } from './ProfileFields'

const STEPS = [
  { id: 'details', label: 'Details' },
  { id: 'plan', label: 'Plan' },
  { id: 'owner', label: 'Owner' },
  { id: 'review', label: 'Review' },
] as const

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
].map((label, index) => ({ value: String(index + 1), label }))

/** Server-side field names that belong to this form. */
const KNOWN_FIELDS: string[] = [...Object.keys(EMPTY_CREATE), 'leadId']

interface CreateCommunityWizardProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** A lead's pre-filled request: the wizard starts from it and links the new community to the lead. */
  draft?: { request: CreateCommunityRequest; suggestedPlanCode: string | null } | undefined
  leadId?: Uuid | undefined
}

function planSummary(plan: PlanView): string {
  return `${formatMoney(plan.priceMonthly)} a month, ${formatMoney(plan.priceYearly)} a year`
}

export function CreateCommunityWizard({
  open,
  onOpenChange,
  draft,
  leadId,
}: CreateCommunityWizardProps) {
  const navigate = useNavigate()
  const plans = usePlans()
  const create = useCreateCommunity()
  const [step, setStep] = useState(0)
  const [formError, setFormError] = useState<string | null>(null)

  const initial = useMemo(() => {
    if (!draft) return EMPTY_CREATE
    const values = draftToForm(draft.request)
    const suggested = plans.data?.find((plan) => plan.code === draft.suggestedPlanCode)
    return suggested && !values.planId ? { ...values, planId: suggested.id } : values
  }, [draft, plans.data])

  const form = useZodForm(createCommunitySchema, { defaultValues: initial })

  // Each time the wizard opens (or a different lead arrives) it starts again from the beginning.
  useEffect(() => {
    if (open) form.reset(initial)
  }, [open, initial, form])

  const values = useWatch({ control: form.control }) as CreateCommunityInput
  const activePlans = (plans.data ?? []).filter((plan) => plan.active)
  const chosenPlan = plans.data?.find((plan) => plan.id === values.planId)
  const last = STEPS.length - 1

  function close(next: boolean) {
    if (create.isPending) return
    if (!next) {
      setStep(0)
      setFormError(null)
    }
    onOpenChange(next)
  }

  async function next() {
    if (step >= last) return
    const valid = await form.trigger([...CREATE_STEP_FIELDS[step]!])
    if (valid) setStep(step + 1)
  }

  async function submit(parsed: CreateCommunityValues) {
    setFormError(null)
    try {
      const community = await create.mutateAsync(toCreateRequest(parsed, leadId))
      notify.success(`${community.name} created`, {
        description: `An invitation was sent to ${parsed.ownerEmail}.`,
      })
      close(false)
      void navigate(paths.superadmin.community(community.id))
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, KNOWN_FIELDS)
      // Send the person back to the step that owns the first field the server rejected.
      const fields = Object.keys(toApiError(error).fieldErrorMap())
      const target = CREATE_STEP_FIELDS.findIndex((group) =>
        group.some((field) => fields.includes(field)),
      )
      if (target >= 0) setStep(target)
      const rest = Object.values(unmatched)
      setFormError(
        rest.length > 0 ? rest.join(' ') : target >= 0 ? null : toApiError(error).message,
      )
    }
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>New community</DialogTitle>
          <DialogDescription>
            {draft
              ? 'Pre-filled from the lead. Check each step, then create.'
              : 'Four short steps. The owner gets an email to set their password.'}
          </DialogDescription>
        </DialogHeader>
        <Stepper steps={STEPS} current={step} label="Create community" />

        <Form {...form}>
          <form
            noValidate
            onSubmit={(event) => {
              event.preventDefault()
              if (step < last) void next()
              else void form.handleSubmit(submit)(event)
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

            {step === 0 ? (
              <>
                <ProfileFields control={form.control as unknown as Control<ProfileInput>} />
                <div className="grid gap-4 sm:grid-cols-2">
                  <FormField
                    control={form.control}
                    name="slug"
                    label="Web address name"
                    description="Lower case letters, digits and hyphens. Made from the name when empty."
                  >
                    {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
                  </FormField>
                  <FormField
                    control={form.control}
                    name="financialYearStartMonth"
                    label="Financial year starts in"
                    required
                  >
                    {({ field, control }) => (
                      <SimpleSelect
                        id={control.id}
                        aria-invalid={control['aria-invalid']}
                        aria-describedby={control['aria-describedby']}
                        options={MONTHS}
                        value={field.value}
                        onValueChange={field.onChange}
                      />
                    )}
                  </FormField>
                </div>
              </>
            ) : null}

            {step === 1 ? (
              <FormField control={form.control} name="planId" label="Plan" required asGroup>
                {({ field }) =>
                  plans.isLoading ? (
                    <Skeleton className="h-32" role="status" aria-label="Loading plans" />
                  ) : plans.isError ? (
                    <p role="alert" className="text-sm text-danger">
                      The plans could not be loaded. Close this window and try again.
                    </p>
                  ) : (
                    <RadioGroup
                      value={field.value}
                      onValueChange={field.onChange}
                      aria-label="Plan"
                    >
                      {activePlans.map((plan) => (
                        <RadioGroupItem
                          key={plan.id}
                          value={plan.id}
                          label={`${plan.name} (${plan.code})`}
                          description={planSummary(plan)}
                        />
                      ))}
                    </RadioGroup>
                  )
                }
              </FormField>
            ) : null}

            {step === 2 ? (
              <div className="grid gap-4">
                <p className="text-sm text-muted">
                  The owner is the community’s first administrator. They receive an email with a
                  link to set their password.
                </p>
                <FormField control={form.control} name="ownerName" label="Owner’s name" required>
                  {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
                </FormField>
                <FormField control={form.control} name="ownerEmail" label="Owner’s email" required>
                  {({ field, control }) => (
                    <Input {...field} {...control} type="email" autoComplete="off" />
                  )}
                </FormField>
              </div>
            ) : null}

            {step === 3 ? (
              <dl className="grid gap-x-6 gap-y-2 rounded border border-brand-100 p-4 text-sm sm:grid-cols-[auto_1fr]">
                <dt className="text-muted">Community</dt>
                <dd>{values.name}</dd>
                <dt className="text-muted">Contact</dt>
                <dd>
                  {[values.contactName, values.contactEmail, values.contactPhone]
                    .filter(Boolean)
                    .join(', ') || 'Not given'}
                </dd>
                <dt className="text-muted">Place</dt>
                <dd>
                  {[values.city, values.state, values.country].filter(Boolean).join(', ') ||
                    'Not given'}
                </dd>
                <dt className="text-muted">Financial year</dt>
                <dd>Starts in {MONTHS[Number(values.financialYearStartMonth) - 1]?.label}</dd>
                <dt className="text-muted">Plan</dt>
                <dd>{chosenPlan ? `${chosenPlan.name} (${planSummary(chosenPlan)})` : ''}</dd>
                <dt className="text-muted">Owner</dt>
                <dd>
                  {values.ownerName}, {values.ownerEmail}
                </dd>
                {leadId ? (
                  <>
                    <dt className="text-muted">From lead</dt>
                    <dd>The lead will be marked as converted.</dd>
                  </>
                ) : null}
              </dl>
            ) : null}

            <div className="flex justify-between gap-2 border-t border-brand-100 pt-4">
              <Button
                type="button"
                variant="secondary"
                onClick={() => (step === 0 ? close(false) : setStep(step - 1))}
                disabled={create.isPending}
              >
                {step === 0 ? 'Cancel' : 'Back'}
              </Button>
              {step < last ? (
                <Button type="submit">Next</Button>
              ) : (
                <Button type="submit" loading={create.isPending}>
                  Create community
                </Button>
              )}
            </div>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
