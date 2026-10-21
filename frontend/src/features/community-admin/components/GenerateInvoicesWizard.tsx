import { useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { formatDate, todayInIst } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { DatePicker } from '@/shared/components/ui/date-picker'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { Input } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { SimpleSelect } from '@/shared/components/ui/select'
import { Stepper } from '@/shared/components/ui/stepper'
import type { GenerateInvoicesRequest, GeneratePreview, GenerateResult } from '@/types'
import { describeRun } from '../billingFormat'
import { generateSchema, type GenerateInput } from '../billingSchemas'
import { useFeePlans, useGenerateInvoices, usePreviewGeneration } from '../hooks/useBilling'

const STEPS = [
  { id: 'choose', label: 'Choose' },
  { id: 'preview', label: 'Check' },
  { id: 'done', label: 'Done' },
] as const

const EMPTY: GenerateInput = { feePlanId: '', period: '', dueDate: '', sendEmails: true }

interface GenerateInvoicesWizardProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  currency?: string
}

/**
 * Bill a fee plan for a period: choose the plan and period, see what would happen (nothing is
 * created yet), then confirm. Running it again for the same period bills nobody twice.
 */
export function GenerateInvoicesWizard({
  open,
  onOpenChange,
  currency = 'INR',
}: GenerateInvoicesWizardProps) {
  const plans = useFeePlans()
  const preview = usePreviewGeneration()
  const generate = useGenerateInvoices()
  const [values, setValues] = useState<GenerateInput>(EMPTY)
  const [errors, setErrors] = useState<Partial<Record<keyof GenerateInput, string>>>({})
  const [shown, setShown] = useState<GeneratePreview | null>(null)
  const [result, setResult] = useState<GenerateResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const activePlans = (plans.data ?? []).filter((plan) => plan.active)
  const plan = activePlans.find((candidate) => candidate.id === values.feePlanId)
  const oneTime = plan?.frequency === 'ONE_TIME'
  const step = result ? 2 : shown ? 1 : 0
  const busy = preview.isPending || generate.isPending

  function request(): GenerateInvoicesRequest {
    return {
      feePlanId: values.feePlanId,
      sendEmails: values.sendEmails,
      ...(values.period.trim() ? { period: values.period.trim() } : {}),
      ...(values.dueDate ? { dueDate: values.dueDate } : {}),
    }
  }

  function close(next: boolean) {
    if (busy) return
    if (!next) {
      setValues(EMPTY)
      setErrors({})
      setShown(null)
      setResult(null)
      setError(null)
    }
    onOpenChange(next)
  }

  async function check() {
    setError(null)
    const parsed = generateSchema(oneTime).safeParse(values)
    if (!parsed.success) {
      const next: Partial<Record<keyof GenerateInput, string>> = {}
      for (const issue of parsed.error.issues) {
        const key = issue.path[0] as keyof GenerateInput
        next[key] ??= issue.message
      }
      setErrors(next)
      return
    }
    setErrors({})
    try {
      setShown(await preview.mutateAsync(request()))
    } catch (caught) {
      setError(toApiError(caught).message)
    }
  }

  async function confirm() {
    setError(null)
    try {
      const done = await generate.mutateAsync(request())
      setResult(done)
      notify.success(describeRun(done))
    } catch (caught) {
      setError(toApiError(caught).message)
    }
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="max-h-[92vh] max-w-xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Generate invoices</DialogTitle>
          <DialogDescription>
            Bill a fee plan for a period. You see what would happen first, and running it again
            never bills anyone twice.
          </DialogDescription>
        </DialogHeader>
        <Stepper steps={STEPS} current={step} label="Generate invoices" />

        {error ? (
          <p
            role="alert"
            className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
          >
            {error}
          </p>
        ) : null}

        {step === 0 ? (
          <div className="grid gap-4">
            <div className="grid gap-1.5">
              <Label htmlFor="gen-plan">Fee plan</Label>
              <SimpleSelect
                id="gen-plan"
                options={activePlans.map((candidate) => ({
                  value: candidate.id,
                  label: `${candidate.name} (${formatMoney(candidate.amount, { currency })})`,
                }))}
                value={values.feePlanId}
                onValueChange={(value) => setValues({ ...values, feePlanId: value })}
                placeholder={plans.isLoading ? 'Loading fee plans' : 'Choose a fee plan'}
                aria-invalid={errors.feePlanId !== undefined}
              />
              {errors.feePlanId ? (
                <p role="alert" className="text-sm text-danger">
                  {errors.feePlanId}
                </p>
              ) : null}
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="gen-period">{oneTime ? 'Name of this billing' : 'Period'}</Label>
              <Input
                id="gen-period"
                value={values.period}
                placeholder={plan?.currentPeriod ?? ''}
                aria-invalid={errors.period !== undefined}
                aria-describedby="gen-period-hint"
                onChange={(event) => setValues({ ...values, period: event.target.value })}
              />
              <p id="gen-period-hint" className="text-xs text-muted">
                {oneTime
                  ? 'For example Annual day 2026.'
                  : plan?.currentPeriod
                    ? `Leave empty for the current period, ${plan.currentPeriod}.`
                    : 'Leave empty for the current period.'}
              </p>
              {errors.period ? (
                <p role="alert" className="text-sm text-danger">
                  {errors.period}
                </p>
              ) : null}
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="gen-due">{oneTime ? 'Due date' : 'Due date (optional)'}</Label>
              <DatePicker
                id="gen-due"
                value={values.dueDate || undefined}
                onValueChange={(value) => setValues({ ...values, dueDate: value ?? '' })}
                min={todayInIst()}
                aria-invalid={errors.dueDate !== undefined}
              />
              {errors.dueDate ? (
                <p role="alert" className="text-sm text-danger">
                  {errors.dueDate}
                </p>
              ) : (
                <p className="text-xs text-muted">
                  {oneTime ? '' : 'Leave empty to use the plan’s due day.'}
                </p>
              )}
            </div>
            <Checkbox
              label="Email each bill to the member"
              description="Only members with an address who agreed to email, within your plan's email allowance."
              checked={values.sendEmails}
              onCheckedChange={(checked) => setValues({ ...values, sendEmails: checked === true })}
            />
            <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
              <Button variant="secondary" onClick={() => close(false)}>
                Cancel
              </Button>
              <Button onClick={() => void check()} loading={preview.isPending}>
                See what would happen
              </Button>
            </div>
          </div>
        ) : null}

        {step === 1 && shown ? (
          <div className="grid gap-4">
            <p className="rounded border border-brand-100 bg-brand-50 p-3 text-sm">
              <strong>Nothing has been created yet.</strong> This is what will happen if you
              continue.
            </p>
            <dl className="grid gap-2 text-sm">
              <div className="flex justify-between gap-3">
                <dt className="text-muted">Fee plan</dt>
                <dd className="font-medium">{shown.feePlanName}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted">Period</dt>
                <dd className="font-medium">{shown.period}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted">Due</dt>
                <dd className="font-medium">{formatDate(shown.dueDate)}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted">Invoices to create</dt>
                <dd className="font-semibold tabular-nums">
                  {shown.willCreate.toLocaleString('en-IN')}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted">Each</dt>
                <dd className="tabular-nums">{formatMoney(shown.amountEach, { currency })}</dd>
              </div>
              <div className="flex justify-between gap-3 border-t border-brand-100 pt-2">
                <dt className="font-medium">Total to bill</dt>
                <dd className="font-heading text-xl font-semibold lining-nums tabular-nums text-brand-900">
                  {formatMoney(shown.totalToBill, { currency })}
                </dd>
              </div>
              {shown.alreadyBilled > 0 ? (
                <div className="flex justify-between gap-3">
                  <dt className="text-muted">Already billed for this period</dt>
                  <dd className="tabular-nums">{shown.alreadyBilled.toLocaleString('en-IN')}</dd>
                </div>
              ) : null}
              {shown.skippedInactive > 0 ? (
                <div className="flex justify-between gap-3">
                  <dt className="text-muted">Inactive members left out</dt>
                  <dd className="tabular-nums">{shown.skippedInactive.toLocaleString('en-IN')}</dd>
                </div>
              ) : null}
            </dl>
            {shown.willCreate === 0 ? (
              <p role="status" className="text-sm">
                Everyone is already billed for this period, so there is nothing to create.
              </p>
            ) : null}
            <div className="flex justify-between gap-2 border-t border-brand-100 pt-4">
              <Button variant="secondary" onClick={() => setShown(null)} disabled={busy}>
                Back
              </Button>
              <Button
                onClick={() => void confirm()}
                loading={generate.isPending}
                disabled={shown.willCreate === 0}
              >
                {`Create ${shown.willCreate.toLocaleString('en-IN')} ${shown.willCreate === 1 ? 'invoice' : 'invoices'}`}
              </Button>
            </div>
          </div>
        ) : null}

        {step === 2 && result ? (
          <div className="grid gap-4">
            <p role="status" className="font-heading text-2xl font-semibold text-brand-900">
              {describeRun(result)}
            </p>
            <ul className="grid gap-1 text-sm">
              {result.firstInvoiceNo ? (
                <li>
                  Numbers {result.firstInvoiceNo} to {result.lastInvoiceNo ?? result.firstInvoiceNo}
                  .
                </li>
              ) : null}
              {result.emailsQueued > 0 ? (
                <li>{result.emailsQueued.toLocaleString('en-IN')} bills are being emailed.</li>
              ) : null}
              {result.emailsSkippedNoAddress > 0 ? (
                <li>
                  {result.emailsSkippedNoAddress.toLocaleString('en-IN')} have no email address.
                </li>
              ) : null}
              {result.emailsSkippedNoConsent > 0 ? (
                <li>
                  {result.emailsSkippedNoConsent.toLocaleString('en-IN')} have not agreed to email.
                </li>
              ) : null}
              {result.emailsSkippedQuota > 0 ? (
                <li>
                  {result.emailsSkippedQuota.toLocaleString('en-IN')} were not emailed because the
                  email allowance of your plan ran out.
                </li>
              ) : null}
            </ul>
            <div className="flex justify-end">
              <Button onClick={() => close(false)}>Close</Button>
            </div>
          </div>
        ) : null}
      </DialogContent>
    </Dialog>
  )
}
