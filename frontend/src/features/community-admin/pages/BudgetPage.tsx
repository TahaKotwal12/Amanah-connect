import { Suspense, lazy } from 'react'
import { useSearchParams } from 'react-router'
import { todayInIst } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { Button } from '@/shared/components/ui/button'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Label } from '@/shared/components/ui/label'
import { PageHeader } from '@/shared/components/ui/page-header'
import { SimpleSelect } from '@/shared/components/ui/select'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatCard } from '@/shared/components/ui/stat-card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/components/ui/tabs'
import type { LedgerType } from '@/types'
import { CategoriesTab, LedgerTab } from '../components/BudgetTabs'
import { ReportsTab } from '../components/ReportsTab'
import { describeRange, rangeFor, type IsoRange, type RangePreset } from '../dateRanges'
import { useLedgerSummary } from '../hooks/useLedger'
import { useWriteAccess } from '../hooks/usePlan'
import { useSettings } from '../hooks/useSettings'

// Recharts is the heaviest dependency here: fetched after the numbers, not before.
const BudgetCharts = lazy(() => import('../components/BudgetCharts'))

const TABS = [
  { id: 'ledger', label: 'Ledger' },
  { id: 'reports', label: 'Reports' },
  { id: 'categories', label: 'Categories' },
] as const
type TabId = (typeof TABS)[number]['id']
const isTab = (value: string | null): value is TabId => TABS.some((tab) => tab.id === value)

const PRESETS: ReadonlyArray<{ value: RangePreset; label: string }> = [
  { value: 'month', label: 'This month' },
  { value: 'quarter', label: 'This quarter' },
  { value: 'year', label: 'This financial year' },
  { value: 'custom', label: 'Choose the days' },
]
const isPreset = (value: string | null): value is RangePreset =>
  PRESETS.some((p) => p.value === value)
const ISO = /^\d{4}-\d{2}-\d{2}$/

function ChartSkeleton() {
  return <Skeleton className="h-80" role="status" aria-label="Loading the charts" />
}

export default function BudgetPage() {
  const [params, setParams] = useSearchParams()
  const write = useWriteAccess()
  const settings = useSettings()
  const currency = settings.data?.currency ?? 'INR'
  const today = todayInIst()

  const requestedTab = params.get('tab')
  const tab: TabId = isTab(requestedTab) ? requestedTab : 'ledger'
  const requestedPreset = params.get('range')
  const preset: RangePreset = isPreset(requestedPreset) ? requestedPreset : 'year'
  const fromParam = params.get('from')
  const toParam = params.get('to')
  const range: IsoRange =
    preset === 'custom'
      ? {
          from: fromParam && ISO.test(fromParam) ? fromParam : '',
          to: toParam && ISO.test(toParam) ? toParam : '',
        }
      : rangeFor(preset, today, settings.data?.financialYearStartMonth ?? 4)
  const rangeReady = range.from !== '' && range.to !== '' && range.from <= range.to

  const summary = useLedgerSummary(rangeReady ? range : {})
  const s = summary.data
  const loading = summary.isLoading

  const wanted = params.get('new')
  const addType: LedgerType | null =
    wanted === 'income' ? 'INCOME' : wanted === 'expense' ? 'EXPENSE' : null

  function update(change: (next: URLSearchParams) => void) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        change(next)
        return next
      },
      { replace: true },
    )
  }

  const money = (amount: string) => formatMoney(amount, { currency })

  return (
    <>
      <PageHeader
        title="Budget"
        description={rangeReady ? describeRange(range) : 'Choose the first and last day.'}
        actions={
          <>
            <Button
              onClick={() => update((n) => n.set('new', 'expense'))}
              disabled={!write.allowed}
              {...(write.reason ? { title: write.reason } : {})}
            >
              Add expense
            </Button>
            <Button
              variant="secondary"
              onClick={() => update((n) => n.set('new', 'income'))}
              disabled={!write.allowed}
            >
              Add income
            </Button>
          </>
        }
      />
      {write.reason ? <p className="-mt-3 mb-4 text-sm text-muted">{write.reason}</p> : null}

      <section aria-label="Period" className="mb-5 flex flex-wrap items-end gap-3">
        <div className="grid gap-1.5">
          <Label htmlFor="budget-range">Period</Label>
          <SimpleSelect
            id="budget-range"
            options={PRESETS.map((p) => ({ value: p.value, label: p.label }))}
            value={preset}
            onValueChange={(value) =>
              update((n) => {
                if (value === 'year') n.delete('range')
                else n.set('range', value)
                if (value !== 'custom') {
                  n.delete('from')
                  n.delete('to')
                }
              })
            }
          />
        </div>
        {preset === 'custom' ? (
          <>
            <div className="grid gap-1.5">
              <Label htmlFor="budget-from">From</Label>
              <DatePicker
                id="budget-from"
                value={range.from || undefined}
                onValueChange={(value) =>
                  update((n) => (value ? n.set('from', value) : n.delete('from')))
                }
                {...(range.to ? { max: range.to } : {})}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="budget-to">To</Label>
              <DatePicker
                id="budget-to"
                value={range.to || undefined}
                onValueChange={(value) =>
                  update((n) => (value ? n.set('to', value) : n.delete('to')))
                }
                {...(range.from ? { min: range.from } : {})}
              />
            </div>
          </>
        ) : null}
      </section>

      {summary.isError ? (
        <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
      ) : (
        <>
          <section aria-label="Summary" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="Income"
              value={s ? money(s.totalIncome) : ''}
              loading={loading || !s}
            />
            <StatCard
              label="Expense"
              value={s ? money(s.totalExpense) : ''}
              loading={loading || !s}
            />
            <StatCard
              label="Net"
              value={s ? money(s.net) : ''}
              hint="Income minus expense"
              loading={loading || !s}
            />
            <StatCard
              label="Opening balance"
              value={s ? money(s.openingBalance) : ''}
              {...(s ? { hint: `Closing balance ${money(s.closingBalance)}` } : {})}
              loading={loading || !s}
            />
          </section>

          <section aria-label="Charts" className="mt-6">
            {s ? (
              <Suspense fallback={<ChartSkeleton />}>
                <BudgetCharts summary={s} currency={currency} />
              </Suspense>
            ) : (
              <ChartSkeleton />
            )}
          </section>
        </>
      )}

      <Tabs
        className="mt-8"
        value={tab}
        onValueChange={(next) =>
          update((n) => {
            // Filters belong to a tab: a new tab starts clean, but the period stays.
            for (const key of [...n.keys()]) {
              if (key !== 'range' && key !== 'from' && key !== 'to') n.delete(key)
            }
            if (next !== 'ledger') n.set('tab', next)
          })
        }
      >
        <TabsList aria-label="Budget sections">
          {TABS.map(({ id, label }) => (
            <TabsTrigger key={id} value={id}>
              {label}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="ledger">
          <LedgerTab
            range={range}
            currency={currency}
            addType={addType}
            onAddClosed={() => update((n) => n.delete('new'))}
          />
        </TabsContent>
        <TabsContent value="reports">
          <ReportsTab />
        </TabsContent>
        <TabsContent value="categories">
          <CategoriesTab />
        </TabsContent>
      </Tabs>
    </>
  )
}
