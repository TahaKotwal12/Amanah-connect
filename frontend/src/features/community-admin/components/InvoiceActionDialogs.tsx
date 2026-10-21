import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import { ReasonDialog } from '@/shared/components/ui/reason-dialog'
import type { InvoiceView } from '@/types'
import { useCancelInvoice, useResendBill } from '../hooks/useBilling'
import { QrDialog } from './QrDialog'
import { RecordPaymentSheet } from './RecordPaymentSheet'

export type InvoiceActionKind = 'pay' | 'qr' | 'resend' | 'cancel'

export interface InvoiceActionTarget {
  kind: InvoiceActionKind
  invoice: InvoiceView
}

interface InvoiceActionDialogsProps {
  target: InvoiceActionTarget | null
  onClose: () => void
  currency?: string
}

/**
 * Everything you can do to one invoice that needs a dialog or a panel: record a payment, show the
 * QR, send the bill again, cancel it. Shared by the invoice list and the invoice page.
 */
export function InvoiceActionDialogs({
  target,
  onClose,
  currency = 'INR',
}: InvoiceActionDialogsProps) {
  const cancel = useCancelInvoice()
  const resend = useResendBill()
  const is = (kind: InvoiceActionKind) => target?.kind === kind
  const invoice = target?.invoice ?? null
  const close = (open: boolean) => {
    if (!open) onClose()
  }

  return (
    <>
      <RecordPaymentSheet
        invoice={is('pay') ? invoice : null}
        onOpenChange={close}
        currency={currency}
      />
      <QrDialog invoice={is('qr') ? invoice : null} onOpenChange={close} currency={currency} />
      <ConfirmDialog
        open={is('resend')}
        onOpenChange={close}
        title={`Send ${invoice?.invoiceNo ?? 'the bill'} again?`}
        description={
          invoice
            ? `${invoice.memberName} gets the bill by email again, with a fresh payment link. They must have an email address and have agreed to emails.`
            : ''
        }
        confirmLabel="Send the bill"
        onConfirm={async () => {
          if (!invoice) return
          try {
            await resend.mutateAsync(invoice.id)
            notify.success(`The bill was sent to ${invoice.memberName}`)
          } catch (error) {
            notify.apiError(error)
            throw error
          }
        }}
      />
      <ReasonDialog
        open={is('cancel')}
        onOpenChange={close}
        title={`Cancel ${invoice?.invoiceNo ?? 'this invoice'}?`}
        description={
          invoice
            ? `It keeps its number and stays on record as cancelled, and ${formatMoney(invoice.balance, { currency })} is no longer owed. If a payment was recorded against it, reverse that payment first.`
            : ''
        }
        confirmLabel="Cancel invoice"
        onSubmit={async (reason) => {
          if (!invoice) return
          await cancel.mutateAsync({ id: invoice.id, reason })
          notify.success(`${invoice.invoiceNo} was cancelled`)
        }}
      />
    </>
  )
}
