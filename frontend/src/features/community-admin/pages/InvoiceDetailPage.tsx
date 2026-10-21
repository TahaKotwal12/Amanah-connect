import { isPayable } from '../invoiceStatus'
import { Printer } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { formatDate, formatDateTime } from '@/lib/dates'
import { downloadBlob } from '@/lib/download'
import { formatMoney, isZeroMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/components/ui/card'
import { ErrorState } from '@/shared/components/ui/error-state'
import { PageHeader } from '@/shared/components/ui/page-header'
import { ReasonDialog } from '@/shared/components/ui/reason-dialog'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { cn } from '@/shared/utils/cn'
import type { PaymentView } from '@/types'
import { fetchReceiptPdf } from '../api/billing'
import { InvoiceActionDialogs, type InvoiceActionTarget } from '../components/InvoiceActionDialogs'
import { QrCard } from '../components/QrDialog'
import { useInvoice, useReversePayment } from '../hooks/useBilling'
import { useWriteAccess } from '../hooks/usePlan'
import { useSettings } from '../hooks/useSettings'
import { buildTimeline } from '../invoiceTimeline'

export default function InvoiceDetailPage() {
  const { id = '' } = useParams()
  const invoice = useInvoice(id)
  const reverse = useReversePayment()
  const write = useWriteAccess()
  const settings = useSettings()
  const currency = settings.data?.currency ?? 'INR'
  const [action, setAction] = useState<InvoiceActionTarget | null>(null)
  const [reversing, setReversing] = useState<PaymentView | null>(null)
  // The same key for a retry of the same reversal, so a dropped connection cannot reverse twice.
  const reversalKey = useRef<{ paymentId: string; key: string } | null>(null)
  const money = (amount: string) => formatMoney(amount, { currency })

  async function downloadReceipt(receiptId: string, receiptNo: string | null) {
    try {
      downloadBlob(`${receiptNo ?? 'receipt'}.pdf`, await fetchReceiptPdf(receiptId))
    } catch (error) {
      notify.apiError(error)
    }
  }

  if (invoice.isError) {
    return (
      <>
        <PageHeader
          title="Invoice"
          breadcrumbs={[{ label: 'Billing', to: paths.admin.billing }, { label: 'Invoice' }]}
        />
        <ErrorState
          headingLevel={2}
          error={invoice.error}
          onRetry={() => void invoice.refetch()}
          retrying={invoice.isFetching}
        />
      </>
    )
  }
  if (!invoice.data) {
    return (
      <div role="status" aria-label="Loading invoice" className="grid gap-4">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-64" />
      </div>
    )
  }

  const { invoice: data, payments } = invoice.data
  const timeline = buildTimeline(invoice.data, currency)
  const payable = isPayable(data)
  const canCancel =
    data.status !== 'CANCELLED' && data.status !== 'DRAFT' && isZeroMoney(data.amountPaid)

  return (
    <>
      <PageHeader
        title={data.invoiceNo}
        description={`${data.description}${data.period ? `, ${data.period}` : ''}`}
        breadcrumbs={[{ label: 'Billing', to: paths.admin.billing }, { label: data.invoiceNo }]}
        actions={
          <>
            <StatusBadge status={data.status} />
            {payable ? (
              <Button
                onClick={() => setAction({ kind: 'pay', invoice: data })}
                disabled={!write.allowed}
                {...(write.reason ? { title: write.reason } : {})}
              >
                Record payment
              </Button>
            ) : null}
            {payable ? (
              <Button
                variant="secondary"
                onClick={() => setAction({ kind: 'resend', invoice: data })}
                disabled={!write.allowed}
              >
                Send the bill again
              </Button>
            ) : null}
            {canCancel ? (
              <Button
                variant="danger"
                onClick={() => setAction({ kind: 'cancel', invoice: data })}
                disabled={!write.allowed}
              >
                Cancel invoice
              </Button>
            ) : null}
            <Button variant="secondary" onClick={() => window.print()}>
              <Printer aria-hidden="true" className="size-4" />
              Print
            </Button>
          </>
        }
      />

      <div className="grid gap-4 lg:grid-cols-[3fr_2fr]">
        <div className="grid content-start gap-4">
          {/* The invoice as a document: this is what prints. */}
          <article
            data-print-card
            aria-label={`Invoice ${data.invoiceNo}`}
            className="grid gap-4 rounded-card border border-brand-100 bg-white p-6 shadow-soft"
          >
            <header className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="font-heading text-xl font-semibold text-brand-900">
                  {settings.data?.name ?? 'Invoice'}
                </p>
                <p className="text-sm text-muted">Invoice {data.invoiceNo}</p>
              </div>
              <div className="text-right text-sm">
                <p>Issued {data.issuedOn ? formatDate(data.issuedOn) : 'not yet'}</p>
                <p className="font-medium">Due {formatDate(data.dueDate)}</p>
              </div>
            </header>
            <p className="text-sm">
              <span className="text-muted">Billed to </span>
              <Link
                to={paths.admin.member(data.memberId)}
                className="font-medium text-brand-700 underline-offset-2 hover:underline"
              >
                {data.memberName}
              </Link>{' '}
              ({data.memberNo})
            </p>
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Invoice lines</caption>
              <thead>
                <tr className="border-b border-brand-100 text-muted">
                  <th scope="col" className="py-2 font-medium">
                    For
                  </th>
                  <th scope="col" className="py-2 text-right font-medium">
                    Amount
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr className="border-b border-brand-50">
                  <td className="py-2">
                    {data.description}
                    {data.period ? `, ${data.period}` : ''}
                  </td>
                  <td className="py-2 text-right tabular-nums">{money(data.amount)}</td>
                </tr>
                <tr>
                  <th scope="row" className="pt-2 text-left font-medium text-muted">
                    Paid so far
                  </th>
                  <td className="pt-2 text-right tabular-nums">{money(data.amountPaid)}</td>
                </tr>
                <tr>
                  <th scope="row" className="pt-1 text-left font-semibold">
                    Balance
                  </th>
                  <td className="pt-1 text-right font-heading text-xl font-semibold lining-nums tabular-nums text-brand-900">
                    {money(data.balance)}
                  </td>
                </tr>
              </tbody>
            </table>
            {data.status === 'CANCELLED' ? (
              <p className="text-sm">
                Cancelled{data.cancelReason ? `: ${data.cancelReason}` : ''}.
              </p>
            ) : null}
          </article>

          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Payments
              </CardTitle>
            </CardHeader>
            <CardContent>
              {payments.length === 0 ? (
                <p className="text-sm text-muted">No payment has been recorded for this invoice.</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <caption className="sr-only">Payments against this invoice</caption>
                    <thead>
                      <tr className="border-b border-brand-100 text-muted">
                        <th scope="col" className="py-2 pr-3 font-medium">
                          Received
                        </th>
                        <th scope="col" className="py-2 pr-3 font-medium">
                          Method
                        </th>
                        <th scope="col" className="py-2 pr-3 text-right font-medium">
                          Amount
                        </th>
                        <th scope="col" className="py-2 pr-3 font-medium">
                          Receipt
                        </th>
                        <th scope="col" className="py-2 font-medium">
                          <span className="sr-only">Actions</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {payments.map((payment) => (
                        <tr key={payment.id} className="border-b border-brand-50 align-top">
                          <td className="py-2 pr-3">{formatDate(payment.receivedOn)}</td>
                          <td className="py-2 pr-3">
                            {payment.method}
                            {payment.reference ? (
                              <span className="block text-xs text-muted">{payment.reference}</span>
                            ) : null}
                          </td>
                          <td
                            className={cn(
                              'py-2 pr-3 text-right tabular-nums',
                              (payment.reversed || payment.kind === 'REVERSAL') && 'text-muted',
                              payment.reversed && 'line-through',
                            )}
                          >
                            {money(payment.amount)}
                          </td>
                          <td className="py-2 pr-3">
                            {payment.receiptId ? (
                              <button
                                type="button"
                                className="text-brand-700 underline underline-offset-2"
                                onClick={() =>
                                  void downloadReceipt(payment.receiptId ?? '', payment.receiptNo)
                                }
                              >
                                {payment.receiptNo}
                              </button>
                            ) : (
                              ''
                            )}
                            {payment.reversed ? (
                              <span className="ml-2">
                                <StatusBadge status="reversed" />
                              </span>
                            ) : null}
                            {payment.kind === 'REVERSAL' ? (
                              <span className="block text-xs text-muted">
                                {payment.reversalReason}
                              </span>
                            ) : null}
                          </td>
                          <td className="py-2">
                            {payment.kind === 'PAYMENT' && !payment.reversed ? (
                              <Button
                                size="sm"
                                variant="danger"
                                disabled={!write.allowed}
                                onClick={() => setReversing(payment)}
                                aria-label={`Reverse the payment of ${money(payment.amount)}`}
                              >
                                Reverse
                              </Button>
                            ) : null}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </CardContent>
          </Card>
        </div>

        <div className="grid content-start gap-4">
          {payable ? (
            <Card>
              <CardHeader>
                <CardTitle as="h2" className="text-lg">
                  Collect by UPI
                </CardTitle>
              </CardHeader>
              <CardContent>
                <QrCard invoice={data} currency={currency} printable={false} />
              </CardContent>
            </Card>
          ) : null}

          <Card>
            <CardHeader>
              <CardTitle as="h2" className="text-lg">
                Timeline
              </CardTitle>
            </CardHeader>
            <CardContent>
              <ol
                aria-label="What happened"
                className="relative grid gap-4 border-l border-brand-100 pl-5"
              >
                {timeline.map((event) => (
                  <li key={event.key} className="relative">
                    <span
                      aria-hidden="true"
                      className={cn(
                        'absolute -left-[1.6rem] top-1.5 size-2.5 rounded-full',
                        event.kind === 'payment'
                          ? 'bg-success'
                          : event.kind === 'reversal' || event.kind === 'cancelled'
                            ? 'bg-danger'
                            : 'bg-brand-500',
                      )}
                    />
                    <p
                      className={cn('font-medium text-brand-900', event.reversed && 'line-through')}
                    >
                      {event.title}
                    </p>
                    <p className="text-sm text-muted">
                      {event.dateOnly ? formatDate(event.at) : formatDateTime(event.at)}
                      {event.receiptNo ? `, receipt ${event.receiptNo}` : ''}
                    </p>
                    {event.detail ? <p className="text-sm">{event.detail}</p> : null}
                  </li>
                ))}
              </ol>
            </CardContent>
          </Card>
        </div>
      </div>

      <InvoiceActionDialogs target={action} onClose={() => setAction(null)} currency={currency} />
      <ReasonDialog
        open={reversing !== null}
        onOpenChange={(open) => !open && setReversing(null)}
        title="Reverse this payment?"
        description={
          reversing ? (
            <div className="grid gap-2">
              <p>
                You are reversing {money(reversing.amount)} received on{' '}
                {formatDate(reversing.receivedOn)}
                {reversing.receiptNo ? ` (receipt ${reversing.receiptNo})` : ''}.
              </p>
              <p>
                Nothing is deleted. A matching negative payment and ledger entry are added, the
                invoice owes the money again, and the receipt stays on record marked as reversed.
                Use this when the money did not really arrive or was recorded by mistake.
              </p>
            </div>
          ) : (
            ''
          )
        }
        confirmLabel="Reverse payment"
        onSubmit={async (reason) => {
          if (!reversing) return
          if (reversalKey.current?.paymentId !== reversing.id) {
            reversalKey.current = { paymentId: reversing.id, key: crypto.randomUUID() }
          }
          await reverse.mutateAsync({
            paymentId: reversing.id,
            body: { reason },
            idempotencyKey: reversalKey.current.key,
          })
          reversalKey.current = null
          notify.success('The payment was reversed')
        }}
      />
    </>
  )
}
