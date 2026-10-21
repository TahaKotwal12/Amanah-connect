import { formatDate } from '@/lib/dates'
import { formatMoney, negateMoney } from '@/lib/money'
import type { InvoiceDetail, Uuid } from '@/types'

export type TimelineKind = 'issued' | 'reminder' | 'payment' | 'reversal' | 'cancelled'

export interface TimelineEvent {
  key: string
  kind: TimelineKind
  /** What happened, in a sentence. */
  title: string
  /** A day (IsoDate) or a moment (IsoInstant), to show with formatDate or formatDateTime. */
  at: string
  /** True when `at` is a calendar day with no time of day. */
  dateOnly: boolean
  detail?: string
  paymentId?: Uuid
  receiptId?: Uuid
  receiptNo?: string
  /** A payment that was reversed later: it is shown, struck, never removed. */
  reversed?: boolean
}

/** A sortable instant for either kind of date: a bare day is the start of that day. */
function sortKey(at: string, dateOnly: boolean): string {
  return dateOnly ? `${at}T00:00:00.000Z` : new Date(at).toISOString()
}

const ORDER: Record<TimelineKind, number> = {
  issued: 0,
  reminder: 1,
  payment: 2,
  reversal: 3,
  cancelled: 4,
}

const REMINDER_WORDS: Record<string, string> = {
  DUE_SOON: 'A reminder that it is falling due was queued',
  OVERDUE: 'An overdue notice was queued',
}

/**
 * Everything that happened to an invoice, oldest first: issued, reminders, payments (with their
 * receipts), reversals and cancellation. Built from the invoice detail; it adds nothing of its own.
 */
export function buildTimeline(detail: InvoiceDetail, currency = 'INR'): TimelineEvent[] {
  const { invoice, payments, reminders } = detail
  const events: Array<TimelineEvent & { sort: string }> = []

  if (invoice.issuedOn) {
    events.push({
      key: 'issued',
      kind: 'issued',
      title: `Issued as ${invoice.invoiceNo}`,
      at: invoice.issuedOn,
      dateOnly: true,
      detail: `${formatMoney(invoice.amount, { currency })}, due ${formatDate(invoice.dueDate)}`,
      sort: sortKey(invoice.issuedOn, true),
    })
  }

  for (const reminder of reminders) {
    events.push({
      key: `reminder-${reminder.kind}-${reminder.on}`,
      kind: 'reminder',
      title: REMINDER_WORDS[reminder.kind] ?? 'A reminder was queued',
      at: reminder.on,
      dateOnly: true,
      sort: sortKey(reminder.on, true),
    })
  }

  for (const payment of payments) {
    const reversal = payment.kind === 'REVERSAL'
    const amount = formatMoney(reversal ? negateMoney(payment.amount) : payment.amount, {
      currency,
    })
    events.push({
      key: payment.id,
      kind: reversal ? 'reversal' : 'payment',
      title: reversal
        ? `${amount} was reversed`
        : `${amount} received by ${payment.method.toLowerCase()}`,
      at: payment.createdAt,
      dateOnly: false,
      paymentId: payment.id,
      ...(reversal
        ? payment.reversalReason
          ? { detail: `Reason: ${payment.reversalReason}` }
          : {}
        : payment.reference
          ? { detail: `Reference ${payment.reference}` }
          : {}),
      ...(payment.receiptId ? { receiptId: payment.receiptId } : {}),
      ...(payment.receiptNo ? { receiptNo: payment.receiptNo } : {}),
      ...(payment.reversed ? { reversed: true } : {}),
      sort: sortKey(payment.createdAt, false),
    })
  }

  if (invoice.cancelledAt) {
    events.push({
      key: 'cancelled',
      kind: 'cancelled',
      title: 'Cancelled',
      at: invoice.cancelledAt,
      dateOnly: false,
      ...(invoice.cancelReason ? { detail: `Reason: ${invoice.cancelReason}` } : {}),
      sort: sortKey(invoice.cancelledAt, false),
    })
  }

  return events
    .sort((a, b) => a.sort.localeCompare(b.sort) || ORDER[a.kind] - ORDER[b.kind])
    .map(({ sort: _sort, ...event }) => event)
}
