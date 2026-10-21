import { Printer } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import { isApiError } from '@/lib/apiError'
import { formatDate } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { CopyField } from '@/shared/components/ui/copy-field'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { Skeleton } from '@/shared/components/ui/skeleton'
import type { InvoiceView, PayLinkView } from '@/types'
import { useCreatePayLink, useInvoiceQr } from '../hooks/useBilling'

/** The sentence that must sit near every QR: paying it does not settle the bill by itself. */
export function ManualConfirmationNotice() {
  return (
    <p role="note" className="rounded border border-gold-500 bg-gold-50 p-3 text-sm text-brand-900">
      <strong>Payments are confirmed by you, by hand.</strong> When someone pays with this QR code
      or link, nothing changes here until you have checked your bank or UPI app and recorded the
      payment.
    </p>
  )
}

interface QrCardProps {
  invoice: InvoiceView
  currency?: string
  /** Print only the code, not the page around it. */
  printable?: boolean
}

/** The UPI QR for an invoice's balance, the notice that payment is confirmed by hand, and a shareable link. */
export function QrCard({ invoice, currency = 'INR', printable = true }: QrCardProps) {
  const qr = useInvoiceQr(invoice.id, true)
  const createLink = useCreatePayLink()
  const [link, setLink] = useState<PayLinkView | null>(null)

  // The PNG arrives as a Blob: show it through an object URL, and release the URL afterwards.
  const blob = qr.data
  const imageUrl = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob])
  useEffect(
    () => () => {
      if (imageUrl) URL.revokeObjectURL(imageUrl)
    },
    [imageUrl],
  )

  async function makeLink() {
    try {
      setLink(await createLink.mutateAsync(invoice.id))
    } catch (error) {
      notify.apiError(error)
    }
  }

  const notConfigured = qr.isError && isApiError(qr.error) && qr.error.code === 'UPI_NOT_CONFIGURED'

  return (
    <div className="grid gap-4">
      <div
        {...(printable ? { 'data-print-card': true } : {})}
        className="grid justify-items-center gap-2 rounded border border-brand-100 bg-white p-4"
      >
        {qr.isLoading ? (
          <Skeleton className="size-56" role="status" aria-label="Loading the QR code" />
        ) : notConfigured ? (
          <p role="alert" className="text-center text-sm">
            UPI is not set up yet, so there is no QR code.{' '}
            <Link to={paths.admin.settings} className="font-medium text-brand-700 underline">
              Add your UPI ID in Settings
            </Link>
          </p>
        ) : qr.isError ? (
          <p role="alert" className="text-center text-sm text-danger">
            The QR code could not be made.{' '}
            <button type="button" className="underline" onClick={() => void qr.refetch()}>
              Try again
            </button>
          </p>
        ) : imageUrl ? (
          <img
            src={imageUrl}
            alt={`UPI QR code for ${formatMoney(invoice.balance, { currency })}, invoice ${invoice.invoiceNo}`}
            className="size-56"
          />
        ) : null}
        <p className="text-center font-heading text-2xl font-semibold lining-nums tabular-nums text-brand-900">
          {formatMoney(invoice.balance, { currency })}
        </p>
        <p className="text-center text-sm text-muted">
          {invoice.invoiceNo}, {invoice.memberName}
        </p>
      </div>
      <ManualConfirmationNotice />

      <div className="grid gap-2">
        <Button variant="secondary" onClick={() => void makeLink()} loading={createLink.isPending}>
          {link ? 'Make another payment link' : 'Make a payment link to share'}
        </Button>
        {link ? (
          <>
            <CopyField label="Payment link" value={link.url} />
            <p className="text-xs text-muted">
              Valid until {formatDate(link.expiresAt)}. It is shown only now.
            </p>
          </>
        ) : null}
      </div>
      {printable ? (
        <div className="flex justify-end">
          <Button variant="secondary" onClick={() => window.print()} disabled={!imageUrl}>
            <Printer aria-hidden="true" className="size-4" />
            Print the QR card
          </Button>
        </div>
      ) : null}
    </div>
  )
}

interface QrDialogProps {
  invoice: InvoiceView | null
  onOpenChange: (open: boolean) => void
  currency?: string
}

/** The QR card in a dialog, for the invoice list. */
export function QrDialog({ invoice, onOpenChange, currency = 'INR' }: QrDialogProps) {
  return (
    <Dialog open={invoice !== null} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[92vh] max-w-lg overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Pay {invoice?.invoiceNo ?? 'this invoice'}</DialogTitle>
          <DialogDescription>
            {invoice
              ? `${invoice.memberName}, ${formatMoney(invoice.balance, { currency })} due ${formatDate(invoice.dueDate)}`
              : ''}
          </DialogDescription>
        </DialogHeader>
        {invoice ? (
          <>
            <QrCard invoice={invoice} currency={currency} />
            <div className="flex justify-end">
              <Button onClick={() => onOpenChange(false)}>Close</Button>
            </div>
          </>
        ) : null}
      </DialogContent>
    </Dialog>
  )
}
