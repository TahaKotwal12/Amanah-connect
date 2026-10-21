import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { formatMonth } from '@/lib/dates'
import { compareMoney, formatMoney, isZeroMoney, sumMoney, toMinorUnits } from '@/lib/money'
import type { LedgerSummaryView, MoneyString } from '@/types'

// Brand palette (tailwind.config.js): fills only, never text. Teal is money in, gold money out.
const TEAL = '#037077'
const GOLD = '#C9A227'
const GRID = '#D6E6E8'
const AXIS = '#5B6F72'
/** Slices of the expense donut: brand teals and golds, then greys. No other hues. */
const SLICES = [
  '#C9A227',
  '#037077',
  '#8A6A12',
  '#065F65',
  '#5B6F72',
  '#E3C766',
  '#04262C',
  '#9DB4B7',
]

/** For drawing only: a bar's height. Never used to add or compare money (that is lib/money). */
const forChart = (amount: MoneyString): number => Number(toMinorUnits(amount)) / 100

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

function Figure({
  title,
  summary,
  children,
  table,
}: {
  title: string
  summary: string
  children: React.ReactNode
  table: React.ReactNode
}) {
  return (
    <figure className="m-0 rounded-card border border-brand-100 bg-white p-5 shadow-soft">
      <figcaption className="font-heading text-lg font-semibold text-brand-900">{title}</figcaption>
      <p className="text-sm text-muted">{summary}</p>
      <div className="mt-3 h-64" aria-hidden="true">
        {children}
      </div>
      {table}
    </figure>
  )
}

interface BudgetChartsProps {
  summary: LedgerSummaryView
  currency: string
}

/** Income against expense by month, and where the expense went. Loaded on demand. */
export default function BudgetCharts({ summary, currency }: BudgetChartsProps) {
  const money = (amount: MoneyString) => formatMoney(amount, { currency })
  const months = summary.byMonth.map((point) => ({
    ...point,
    label: formatMonth(point.month),
    incomeValue: forChart(point.income),
    expenseValue: forChart(point.expense),
  }))
  const noMonths = months.every((m) => isZeroMoney(m.income) && isZeroMoney(m.expense))

  const expenseCategories = summary.byCategory
    .filter((category) => category.type === 'EXPENSE' && !isZeroMoney(category.total))
    .sort((a, b) => compareMoney(b.total, a.total))
  const expenseTotal = sumMoney(expenseCategories.map((category) => category.total))
  const slices = expenseCategories.map((category) => ({
    ...category,
    value: forChart(category.total),
  }))

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Figure
        title="Income and expense by month"
        summary={
          noMonths
            ? 'Nothing was recorded in this period.'
            : `Income ${money(summary.totalIncome)}, expense ${money(summary.totalExpense)}.`
        }
        table={
          <table className="sr-only">
            <caption>Income and expense by month</caption>
            <thead>
              <tr>
                <th scope="col">Month</th>
                <th scope="col">Income</th>
                <th scope="col">Expense</th>
                <th scope="col">Net</th>
              </tr>
            </thead>
            <tbody>
              {months.map((point) => (
                <tr key={point.month}>
                  <th scope="row">{point.label}</th>
                  <td>{money(point.income)}</td>
                  <td>{money(point.expense)}</td>
                  <td>{money(point.net)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        }
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={months} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke={GRID} />
            <XAxis dataKey="label" interval="preserveStartEnd" {...axisProps} />
            <YAxis
              width={56}
              tickFormatter={shortRupees}
              {...(noMonths ? { domain: [0, 1], ticks: [0] } : {})}
              {...axisProps}
            />
            <Tooltip
              cursor={{ fill: '#EEF8F8' }}
              formatter={(_value, name, item) => {
                const point = item.payload as (typeof months)[number]
                return [money(name === 'Income' ? point.income : point.expense), name]
              }}
            />
            <Legend />
            <Bar name="Income" dataKey="incomeValue" fill={TEAL} isAnimationActive={false} />
            <Bar name="Expense" dataKey="expenseValue" fill={GOLD} isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </Figure>

      <Figure
        title="Where the expense went"
        summary={
          slices.length === 0
            ? 'No expense was recorded in this period.'
            : `${money(expenseTotal)} across ${slices.length} ${slices.length === 1 ? 'category' : 'categories'}.`
        }
        table={
          <table className="sr-only">
            <caption>Expense by category</caption>
            <thead>
              <tr>
                <th scope="col">Category</th>
                <th scope="col">Expense</th>
              </tr>
            </thead>
            <tbody>
              {slices.map((slice) => (
                <tr key={slice.categoryId}>
                  <th scope="row">{slice.name}</th>
                  <td>{money(slice.total)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        }
      >
        {slices.length === 0 ? (
          <p className="flex h-full items-center justify-center text-sm text-muted">
            Nothing to show
          </p>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={slices}
                dataKey="value"
                nameKey="name"
                innerRadius="55%"
                outerRadius="85%"
                paddingAngle={1}
                isAnimationActive={false}
              >
                {slices.map((slice, index) => (
                  <Cell key={slice.categoryId} fill={SLICES[index % SLICES.length] ?? GOLD} />
                ))}
              </Pie>
              <Tooltip
                formatter={(_value, name, item) => [
                  money((item.payload as (typeof slices)[number]).total),
                  name,
                ]}
              />
              <Legend />
            </PieChart>
          </ResponsiveContainer>
        )}
      </Figure>
    </div>
  )
}
