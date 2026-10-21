import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { formatMonth } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import type { MonthlySeries } from '@/types'

// Brand palette (tailwind.config.js): teal for money, gold for counts. Fills only, never text.
const TEAL = '#037077'
const GOLD = '#C9A227'
const GRID = '#D6E6E8'
const AXIS = '#5B6F72'

interface MonthlyChartsProps {
  series: MonthlySeries
}

interface Row {
  month: string
  label: string
  /** For drawing only. The exact string is what the table and tooltip show. */
  revenueValue: number
  revenue: string
  newCommunities: number
}

function toRows(series: MonthlySeries): Row[] {
  return series.months.map((point) => ({
    month: point.month,
    label: formatMonth(point.month),
    revenueValue: Number(point.revenue),
    revenue: point.revenue,
    newCommunities: point.newCommunities,
  }))
}

/** "₹1.2L" style axis ticks in the Indian system. */
function shortRupees(value: number): string {
  if (value >= 10_000_000) return `₹${+(value / 10_000_000).toFixed(1)}Cr`
  if (value >= 100_000) return `₹${+(value / 100_000).toFixed(1)}L`
  if (value >= 1000) return `₹${+(value / 1000).toFixed(1)}k`
  return `₹${value}`
}

function ChartFigure({
  title,
  summary,
  rows,
  column,
  children,
}: {
  title: string
  summary: string
  rows: Row[]
  column: { header: string; value: (row: Row) => string }
  children: React.ReactNode
}) {
  return (
    <figure className="m-0 rounded-card border border-brand-100 bg-white p-5 shadow-soft">
      <figcaption className="font-heading text-lg font-semibold text-brand-900">{title}</figcaption>
      <p className="text-sm text-muted">{summary}</p>
      <div className="mt-3 h-64" aria-hidden="true">
        {children}
      </div>
      {/* The same numbers as text, for screen readers and anyone who cannot read a chart. */}
      <table className="sr-only">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Month</th>
            <th scope="col">{column.header}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.month}>
              <th scope="row">{row.label}</th>
              <td>{column.value(row)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}

const axisProps = {
  tickLine: false,
  axisLine: { stroke: GRID },
  tick: { fill: AXIS, fontSize: 12 },
} as const

/** Two charts for the last twelve months. Loaded on demand: Recharts is not in the first bundle. */
export default function MonthlyCharts({ series }: MonthlyChartsProps) {
  const rows = toRows(series)
  const totalRevenue = rows.reduce((sum, row) => sum + row.revenueValue, 0)
  const totalNew = rows.reduce((sum, row) => sum + row.newCommunities, 0)
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <ChartFigure
        title="Revenue by month"
        summary={
          totalRevenue === 0
            ? 'No payments recorded in these months.'
            : `${formatMoney(totalRevenue.toFixed(2))} over ${rows.length} months.`
        }
        rows={rows}
        column={{ header: 'Revenue', value: (row) => formatMoney(row.revenue) }}
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke={GRID} />
            <XAxis dataKey="label" interval="preserveStartEnd" {...axisProps} />
            <YAxis
              width={56}
              tickFormatter={shortRupees}
              {...(totalRevenue === 0 ? { domain: [0, 1], ticks: [0] } : {})}
              {...axisProps}
            />
            <Tooltip
              cursor={{ fill: '#EEF8F8' }}
              formatter={(_value, _name, item) => [
                formatMoney((item.payload as Row).revenue),
                'Revenue',
              ]}
            />
            <Bar dataKey="revenueValue" fill={TEAL} isAnimationActive={false} radius={0} />
          </BarChart>
        </ResponsiveContainer>
      </ChartFigure>

      <ChartFigure
        title="New communities by month"
        summary={
          totalNew === 0
            ? 'No communities were created in these months.'
            : `${totalNew} new ${totalNew === 1 ? 'community' : 'communities'} over ${rows.length} months.`
        }
        rows={rows}
        column={{ header: 'New communities', value: (row) => String(row.newCommunities) }}
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke={GRID} />
            <XAxis dataKey="label" interval="preserveStartEnd" {...axisProps} />
            <YAxis
              width={32}
              allowDecimals={false}
              {...(totalNew === 0 ? { domain: [0, 1], ticks: [0] } : {})}
              {...axisProps}
            />
            <Tooltip
              cursor={{ fill: '#FBF6E3' }}
              formatter={(value) => [String(value), 'New communities']}
            />
            <Bar dataKey="newCommunities" fill={GOLD} isAnimationActive={false} radius={0} />
          </BarChart>
        </ResponsiveContainer>
      </ChartFigure>
    </div>
  )
}
