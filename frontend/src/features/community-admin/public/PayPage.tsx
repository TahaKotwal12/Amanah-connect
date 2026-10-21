import { Smartphone } from 'lucide-react'
import { useParams } from 'react-router'
import { formatDate } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { Button } from '@/shared/components/ui/button'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { usePublicPay } from '../hooks/usePay'
import { CommunityHeader } from './CommunityHeader'

/** Only a UPI link is ever opened as an app link: anything else from the server is not trusted. */
function safeUpiLink(link: string | undefined): string | null {
  return link && /^upi:\/\//i.test(link) ? link : null
}

function Unavailable() {
  return (
    <div className="rounded-card border border-brand-100 bg-white p-6 shadow-soft">
      <h1 className="font-heading text-2xl font-semibold text-brand-900">
        This payment link cannot be used
      </h1>
      <p className="mt-2 text-muted">
        It may be wrong, or too old. Ask the community for the latest bill.
      </p>
    </div>
  )
}

export default function PayPage() {
  const { token = '' } = useParams()
  const pay = usePublicPay(token)

  if (pay.isLoading) {
    return <Skeleton className="h-96" role="status" aria-label="Loading the payment details" />
  }
  if (pay.isError || !pay.data) return <Unavailable />

  const view = pay.data
  const currency = view.currency ?? 'INR'
  const money = (amount: string | undefined) =>
    amount === undefined ? '' : formatMoney(amount, { currency })
  const upiLink = safeUpiLink(view.upi?.link)
  const settled = view.status === 'PAID' || view.status === 'CANCELLED'

  return (
    <>
      <CommunityHeader name={view.communityName} logoUrl={view.logoUrl} />
      <div className="rounded-card border border-brand-100 bg-white p-6 shadow-soft">
        <h1 className="font-heading text-2xl font-semibold text-brand-900">
          {settled ? 'Nothing to pay' : 'Payment details'}
        </h1>

        <dl className="mt-4 grid gap-3 text-sm">
          <div className="flex flex-wrap justify-between gap-2">
            <dt className="text-muted">Bill</dt>
            <dd className="font-medium">{view.invoiceNo}</dd>
          </div>
          {view.description ? (
            <div className="flex flex-wrap justify-between gap-2">
              <dt className="text-muted">For</dt>
              <dd className="font-medium">
                {view.description}
                {view.period ? `, ${view.period}` : ''}
              </dd>
            </div>
          ) : null}
          <div className="flex flex-wrap justify-between gap-2">
            <dt className="text-muted">Due</dt>
            <dd className="font-medium">{view.dueDate ? formatDate(view.dueDate) : ''}</dd>
          </div>
          <div className="flex flex-wrap justify-between gap-2">
            <dt className="text-muted">Status</dt>
            <dd>{view.status ? <StatusBadge status={view.status} /> : null}</dd>
          </div>
          {view.amountPaid && view.amountPaid !== '0.00' ? (
            <div className="flex flex-wrap justify-between gap-2">
              <dt className="text-muted">Already paid</dt>
              <dd className="font-medium tabular-nums">{money(view.amountPaid)}</dd>
            </div>
          ) : null}
        </dl>

        <p className="mt-5 rounded border border-brand-100 bg-brand-50 p-4">
          <span className="block text-sm text-muted">{settled ? 'Total' : 'Amount due'}</span>
          <span className="font-heading text-3xl font-semibold tabular-nums lining-nums text-brand-900">
            {money(settled ? view.amount : view.balanceDue)}
          </span>
        </p>

        {view.payable && view.upi ? (
          <section aria-label="Pay with UPI" className="mt-6 grid justify-items-center gap-3">
            {view.upi.qrCodePngBase64 ? (
              <img
                src={`data:image/png;base64,${view.upi.qrCodePngBase64}`}
                alt={`UPI QR code to pay ${money(view.balanceDue)} to ${view.upi.payeeName ?? view.communityName}`}
                className="size-56"
              />
            ) : null}
            <p className="text-center text-sm text-muted">
              Scan with any UPI app, or pay {view.upi.payeeName ? `${view.upi.payeeName}` : 'the community'} on
              this phone.
            </p>
            {upiLink ? (
              <Button asChild className="w-full">
                <a href={upiLink}>
                  <Smartphone aria-hidden="true" className="size-4" />
                  Open in my UPI app
                </a>
              </Button>
            ) : null}
          </section>
        ) : null}

        {!settled ? (
          <div
            role="note"
            className="mt-6 rounded border border-gold-500 bg-gold-50 p-4 text-sm text-brand-900"
          >
            <p className="font-semibold">Your payment is confirmed by the community.</p>
            <p className="mt-1">
              Paying here does not update your account at once. {view.communityName}{' '}
              checks its bank or UPI app, then marks this bill paid and sends you a receipt.
            </p>
            {view.notice ? <p className="mt-2">{view.notice}</p> : null}
          </div>
        ) : null}
      </div>
    </>
  )
}
