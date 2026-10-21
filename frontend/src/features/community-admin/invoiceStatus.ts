import type { InvoiceView } from '@/types'

/** Invoices that can still be paid: issued, partly paid or overdue. */
export function isPayable(invoice: InvoiceView): boolean {
  return invoice.status === 'ISSUED' || invoice.status === 'PARTIAL' || invoice.status === 'OVERDUE'
}
