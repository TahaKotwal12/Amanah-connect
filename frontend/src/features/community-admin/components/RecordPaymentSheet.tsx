import { CircleCheck, Download, Mail } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { isApiError, toApiError } from '@/lib/apiError'
import { formatDate, todayInIst } from '@/lib/dates'
import { downloadBlob } from '@/lib/download'
import { formatMoney, normalizeMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
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
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { InvoiceView, PaymentResult } from '@/types'
import { fetchReceiptPdf } from '../api/billing'
import {
  PAYMENT_METHODS,
  recordPaymentSchema,
  toRecordPaymentRequest,
  type RecordPaymentInput,
  type RecordPaymentValues,
} from '../billingSchemas'
import { useRecordPayment, useResendReceipt } from '../hooks/useBilling'
import { useQueryClient } from '@tanstack/react-query'
import { communityAdminKeys } from '../queryKeys'

interface RecordPaymentSheetProps {
  invoice: InvoiceView | null
  onOpenChange: (open: boolean) => void
  currency?: string
}

const FIELDS = ['amount', 'method', 'reference', 'receivedOn']

function ReceiptPanel({
  result,
  currency,
  onDone,
}: {
  result: PaymentResult
  currency: string
  onDone: () => void
}) {
  const resend = useResendReceipt()
  const [downloading, setDownloading] = useState(false)
  const { payment, invoice } = result
  const receiptId = payment.receiptId

  async function download() {
    if (!receiptId) return
    setDownloading(true)
    try {
      downloadBlob(`${payment.receiptNo ?? 'receipt'}.pdf`, await fetchReceiptPdf(receiptId))
    } catch (error) {
      notify.apiError(error)
    } finally {
      setDownloading(false)
    }
  }

  async function emailAgain() {
    if (!receiptId) return
    try {
      await resend.mutateAsync(receiptId)
      notify.success('The receipt was emailed again')
    } catch (error) {
      notify.apiError(error)
    }
  }

  return (
    <div className="grid gap-4" role="status">
      <div>
        <CircleCheck aria-hidden="true" className="size-9 text-success" />
        <h3 className="mt-2 font-heading text-xl font-semibold text-brand-900">Payment recorded</h3>
        {result.replayed ? (
          <p className="mt-1 text-sm text-muted">
            This payment had already been recorded, so nothing was added twice.
          </p>
        ) : null}
      </div>
      <dl className="grid gap-2 rounded border border-brand-100 bg-brand-50 p-4 text-sm">
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Receipt</dt>
          <dd className="font-semibold">{payment.receiptNo ?? 'Not issued'}</dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-muted">Amount</dt>
          <dd className="font-semibold tabular-nums">
            {formatMoney(payment.amount, { currency })}
          </dd>
        </div>
        {invoice ? (
          <>
            <div className="flex justify-between gap-3">
              <dt className="text-muted">Invoice {invoice.invoiceNo}</dt>
              <dd>
                <StatusBadge status={invoice.status} />
              </dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt className="text-muted">Still owed</dt>
              <dd className="font-semibold tabular-nums">
                {formatMoney(invoice.balance, { currency })}
              </dd>
            </div>
          </>
        ) : null}
      </dl>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="secondary"
          onClick={() => void download()}
          loading={downloading}
          disabled={!receiptId}
        >
          <Download aria-hidden="true" className="size-4" />
          Download receipt (PDF)
        </Button>
        <Button
          variant="secondary"
          onClick={() => void emailAgain()}
          loading={resend.isPending}
          disabled={!receiptId}
        >
          <Mail aria-hidden="true" className="size-4" />
          Email it again
        </Button>
      </div>
      <div className="flex justify-end border-t border-brand-100 pt-4">
        <Button onClick={onDone}>Done</Button>
      </div>
    </div>
  )
}

function PaymentForm({
  invoice,
  currency,
  onClose,
  onRecorded,
}: {
  invoice: InvoiceView
  currency: string
  onClose: () => void
  onRecorded: (result: PaymentResult) => void
}) {
  const record = useRecordPayment()
  const client = useQueryClient()
  const schema = useMemo(
    () => recordPaymentSchema(invoice.balance, currency),
    [invoice.balance, currency],
  )
  const form = useZodForm(schema, {
    defaultValues: {
      amount: normalizeMoney(invoice.balance),
      method: 'UPI',
      reference: '',
      receivedOn: todayInIst(),
    } satisfies RecordPaymentInput,
  })
  const [formError, setFormError] = useState<string | null>(null)
  // One key per intended payment: kept for a retry of the very same request (a dropped connection
  // must not record it twice), replaced as soon as anything about the request changes.
  const attempt = useRef<{ signature: string; key: string } | null>(null)

  async function submit(values: RecordPaymentValues) {
    setFormError(null)
    const body = toRecordPaymentRequest(values, invoice.version)
    const signature = JSON.stringify(body)
    if (attempt.current?.signature !== signature) {
      attempt.current = { signature, key: crypto.randomUUID() }
    }
    try {
      const done = await record.mutateAsync({
        invoiceId: invoice.id,
        body,
        idempotencyKey: attempt.current.key,
      })
      attempt.current = null
      onRecorded(done)
    } catch (error) {
      if (isApiError(error) && error.code === 'OVERPAYMENT') {
        form.setError('amount', {
          type: 'server',
          message: `That is more than the balance of ${formatMoney(invoice.balance, { currency })}. Enter the balance or less.`,
        })
        return
      }
      if (isApiError(error) && error.code === 'VERSION_CONFLICT') {
        void client.invalidateQueries({ queryKey: communityAdminKeys.invoices.all })
        setFormError(
          'This invoice was changed while you were recording the payment, so nothing was saved. It has been reloaded: check the balance and try again.',
        )
        attempt.current = null
        return
      }
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  return (
    <Form {...form}>
      <form
        noValidate
        onSubmit={(event) => {
          void form.handleSubmit(submit)(event)
        }}
        className="grid gap-4"
      >
        <dl className="grid gap-1 rounded border border-brand-100 bg-brand-50 p-3 text-sm">
          <div className="flex justify-between gap-3">
            <dt className="text-muted">Invoice</dt>
            <dd className="font-medium">
              {invoice.invoiceNo}, {invoice.memberName}
            </dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-muted">Due</dt>
            <dd>{formatDate(invoice.dueDate)}</dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-muted">Balance</dt>
            <dd className="font-semibold tabular-nums">
              {formatMoney(invoice.balance, { currency })}
            </dd>
          </div>
        </dl>
        <p className="text-sm text-muted">
          Record only money you have already received and checked in your bank or UPI app. This does
          not collect anything.
        </p>

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
          name="amount"
          label="Amount received"
          required
          description="A part payment is fine. It cannot be more than the balance."
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
        <FormField control={form.control} name="method" label="How was it paid?" required>
          {({ field, control }) => (
            <SimpleSelect
              id={control.id}
              options={PAYMENT_METHODS}
              value={field.value}
              onValueChange={field.onChange}
            />
          )}
        </FormField>
        <FormField
          control={form.control}
          name="reference"
          label="Reference"
          description="The UPI or bank reference, or the cheque number."
        >
          {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
        </FormField>
        <FormField control={form.control} name="receivedOn" label="Received on" required>
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

        <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
          <Button type="button" variant="secondary" onClick={onClose} disabled={record.isPending}>
            Cancel
          </Button>
          <Button type="submit" loading={record.isPending}>
            Record payment
          </Button>
        </div>
      </form>
    </Form>
  )
}

/**
 * Record money received for one invoice. The form is keyed by the invoice and its balance, so it
 * always starts from what is owed now; the receipt (number, PDF, email) appears in the same panel.
 */
export function RecordPaymentSheet({
  invoice,
  onOpenChange,
  currency = 'INR',
}: RecordPaymentSheetProps) {
  // Kept here, not in the form: recording changes the invoice, which restarts the form, and the
  // receipt must stay on screen until the person is done with it.
  const [result, setResult] = useState<PaymentResult | null>(null)
  function change(open: boolean) {
    if (!open) setResult(null)
    onOpenChange(open)
  }
  return (
    <Sheet open={invoice !== null} onOpenChange={change}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Record a payment</SheetTitle>
          <SheetDescription>
            Payments are confirmed by you: record one after you have checked that the money arrived.
          </SheetDescription>
        </SheetHeader>
        {result ? (
          <ReceiptPanel result={result} currency={currency} onDone={() => change(false)} />
        ) : invoice ? (
          <PaymentForm
            key={invoice.id}
            invoice={invoice}
            currency={currency}
            onClose={() => change(false)}
            onRecorded={setResult}
          />
        ) : null}
      </SheetContent>
    </Sheet>
  )
}
