import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { formatMonth } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import type { TrendPoint } from '@/types'

// Brand palette (tailwind.config.js): teal for what was collected, gold for what was billed.
const TEAL = '#037077'
const GOLD = '#C9A227'
const GRID = '#D6E6E8'
const AXIS = '#5B6F72'

interface Row {
  month: string
  label: string
  /** For drawing only. The exact strings are what the table and tooltip show. */
  billedValue: number
  collectedValue: number
  billed: string
  collected: string
}

function shortRupees(value: number): string {
  if (value >= 10_000_000) return `₹${+(value / 10_000_000).toFixed(1)}Cr`
  if (value >= 100_000) return `₹${+(value / 100_000).toFixed(1)}L`
  if (value >= 1000) return `₹${+(value / 1000).toFixed(1)}k`
  return `₹${value}`
}

const axisProps = {
  tickLine: false,
  axisLine: { stroke: GRID },
  tick: { fill: AXIS, fontSize: 12 },
} as const

/** Billed against collected, month by month. Loaded on demand: Recharts is not in the first bundle. */
export default function CollectionTrendChart({
  points,
  currency,
}: {
  points: TrendPoint[]
  currency: string
}) {
  const rows: Row[] = points.map((point) => ({
    month: point.month,
    label: formatMonth(point.month),
    billedValue: Number(point.billed),
    collectedValue: Number(point.collected),
    billed: point.billed,
    collected: point.collected,
  }))
  const total = rows.reduce((sum, row) => sum + row.billedValue + row.collectedValue, 0)
  const money = (amount: string) => formatMoney(amount, { currency })

  return (
    <figure className="m-0 rounded-card border border-brand-100 bg-white p-5 shadow-soft">
      <figcaption className="font-heading text-lg font-semibold text-brand-900">
        Billed and collected
      </figcaption>
      <p className="text-sm text-muted">
        {total === 0
          ? 'Nothing has been billed in these months.'
          : `The last ${rows.length} months: what was invoiced, and what was paid against it.`}
      </p>
      <div className="mt-3 h-64" aria-hidden="true">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke={GRID} />
            <XAxis dataKey="label" interval="preserveStartEnd" {...axisProps} />
            <YAxis
              width={56}
              tickFormatter={shortRupees}
              {...(total === 0 ? { domain: [0, 1], ticks: [0] } : {})}
              {...axisProps}
            />
            <Tooltip
              cursor={{ fill: '#EEF8F8' }}
              formatter={(_value, name, item) => [
                money((item.payload as Row)[name === 'Billed' ? 'billed' : 'collected']),
                name,
              ]}
            />
            <Legend />
            <Bar name="Billed" dataKey="billedValue" fill={GOLD} isAnimationActive={false} />
            <Bar name="Collected" dataKey="collectedValue" fill={TEAL} isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <table className="sr-only">
        <caption>Billed and collected by month</caption>
        <thead>
          <tr>
            <th scope="col">Month</th>
            <th scope="col">Billed</th>
            <th scope="col">Collected</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.month}>
              <th scope="row">{row.label}</th>
              <td>{money(row.billed)}</td>
              <td>{money(row.collected)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}
