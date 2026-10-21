import { formatMoney } from '@/lib/money'
import { StatusBadge } from '@/shared/components/ui/status-badge'

/** Invented figures for illustration only. The mock says so on its face. */
const BARS = [
  { month: 'Jan', height: 54 },
  { month: 'Feb', height: 64 },
  { month: 'Mar', height: 48 },
  { month: 'Apr', height: 72 },
  { month: 'May', height: 66 },
  { month: 'Jun', height: 86 },
]

const PAYMENTS = [
  { who: 'Member 014', amount: '1500.00', status: 'paid' },
  { who: 'Member 027', amount: '1500.00', status: 'pending' },
  { who: 'Member 031', amount: '3000.00', status: 'overdue' },
]

/**
 * A dashboard drawn in React, not a screenshot. Screen readers get one description of it instead of
 * the sample numbers, which would read as real data.
 */
export function DashboardMock() {
  return (
    <div
      role="img"
      aria-label="Illustration of the Amanah Connect dashboard, showing collected dues, outstanding dues, a monthly chart and recent payments, with sample figures."
      className="w-full max-w-md rounded-card bg-white p-4 text-left shadow-lift ring-1 ring-brand-100 sm:p-5"
    >
      <div aria-hidden="true">
        <div className="flex items-center justify-between gap-2">
          <p className="font-heading text-base font-semibold text-brand-900">Dashboard</p>
          <span className="rounded border border-gold-200 bg-gold-50 px-2 py-0.5 text-xs font-semibold text-gold-600">
            Sample data
          </span>
        </div>

        <div className="mt-4 grid grid-cols-3 gap-2">
          {[
            ['Collected', formatMoney('124500', { symbol: true }).replace('.00', '')],
            ['Outstanding', formatMoney('38200').replace('.00', '')],
            ['Open complaints', '3'],
          ].map(([label, value]) => (
            <div key={label} className="rounded bg-brand-50 p-2.5">
              <p className="text-[11px] font-medium text-muted">{label}</p>
              <p className="mt-1 text-sm font-semibold tabular-nums text-brand-900">{value}</p>
            </div>
          ))}
        </div>

        <div className="mt-4">
          <p className="text-xs font-medium text-muted">Collected, last 6 months</p>
          <div className="mt-2 flex h-24 items-end gap-2 border-b border-brand-100">
            {BARS.map((bar) => (
              <div key={bar.month} className="flex h-full flex-1 flex-col items-center justify-end">
                <div
                  className="w-full rounded-t bg-brand-600"
                  style={{ height: `${bar.height}%` }}
                />
              </div>
            ))}
          </div>
          <div className="mt-1 flex gap-2">
            {BARS.map((bar) => (
              <span key={bar.month} className="flex-1 text-center text-[11px] text-muted">
                {bar.month}
              </span>
            ))}
          </div>
        </div>

        <ul className="mt-4 grid gap-2">
          {PAYMENTS.map((payment) => (
            <li
              key={payment.who}
              className="flex items-center justify-between gap-2 rounded border border-brand-100 px-3 py-2"
            >
              <span className="text-sm font-medium text-ink">{payment.who}</span>
              <span className="flex items-center gap-2">
                <span className="text-sm tabular-nums text-muted">
                  {formatMoney(payment.amount)}
                </span>
                <StatusBadge status={payment.status} />
              </span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
