import { Download, Lock } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link } from 'react-router'
import { isApiError, toApiError } from '@/lib/apiError'
import { todayInIst } from '@/lib/dates'
import { downloadBlob } from '@/lib/download'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { Label } from '@/shared/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/shared/components/ui/radio-group'
import { SimpleSelect } from '@/shared/components/ui/select'
import type { ReportFormat, ReportKind } from '@/types'
import { downloadReport, type ReportRequest } from '../api/reports'
import { financialYear, thisMonth, thisQuarter, type IsoRange } from '../dateRanges'
import { useCommunityPlan } from '../hooks/usePlan'
import { useSettings } from '../hooks/useSettings'
import { MemberPicker } from './MemberPicker'

interface ReportChoice {
  kind: ReportKind
  label: string
  about: string
  /** Which controls the report uses. */
  uses: { range?: boolean; member?: boolean; asOf?: boolean }
}

const REPORTS: readonly ReportChoice[] = [
  {
    kind: 'INCOME_EXPENSE',
    label: 'Income and expense statement',
    about: 'Everything that came in and went out, with the totals.',
    uses: { range: true },
  },
  {
    kind: 'CATEGORY_BREAKDOWN',
    label: 'Category breakdown',
    about: 'Income and expense added up by category.',
    uses: { range: true },
  },
  {
    kind: 'COLLECTION',
    label: 'Collection report',
    about: 'What was billed, what was collected and what is still owed.',
    uses: { range: true },
  },
  {
    kind: 'MEMBER_DUES',
    label: 'Member dues statement',
    about: 'Every bill and payment of one member.',
    uses: { member: true },
  },
  {
    kind: 'DEFAULTERS',
    label: 'Defaulters',
    about: 'Who owes money, as of a day.',
    uses: { asOf: true },
  },
  {
    kind: 'RECEIPTS_REGISTER',
    label: 'Receipts register',
    about: 'Every receipt issued in a period.',
    uses: { range: true },
  },
]

type Preset = 'month' | 'quarter' | 'year' | 'custom'

/** The reports page: pick a report, a format and a period, and download it. */
export function ReportsTab() {
  const plan = useCommunityPlan()
  const settings = useSettings()
  const today = todayInIst()
  const fyStart = settings.data?.financialYearStartMonth ?? 4

  const [kind, setKind] = useState<ReportKind>('INCOME_EXPENSE')
  const [format, setFormat] = useState<ReportFormat>('CSV')
  const [preset, setPreset] = useState<Preset>('year')
  const [custom, setCustom] = useState<IsoRange>({ from: '', to: '' })
  const [memberId, setMemberId] = useState('')
  const [asOf, setAsOf] = useState(today)
  const [progress, setProgress] = useState<number | null | 'working'>(null)
  const [error, setError] = useState<string | null>(null)
  const [pdfRefused, setPdfRefused] = useState(false)
  const abort = useRef<AbortController | null>(null)

  const choice = REPORTS.find((report) => report.kind === kind) ?? REPORTS[0]
  const features = plan.data?.features ?? {}
  const csvIncluded = features.csv_export === true
  const pdfIncluded = features.pdf_reports === true && !pdfRefused
  const loadedPlan = plan.data !== undefined
  const formatAllowed = format === 'PDF' ? pdfIncluded : csvIncluded
  const busy = progress !== null

  const range: IsoRange =
    preset === 'month'
      ? thisMonth(today)
      : preset === 'quarter'
        ? thisQuarter(today)
        : preset === 'year'
          ? financialYear(today, fyStart)
          : custom

  const missing =
    choice?.uses.range && (range.from === '' || range.to === '')
      ? 'Choose the first and last day.'
      : choice?.uses.member && memberId === ''
        ? 'Choose a member.'
        : null

  async function download() {
    if (!choice) return
    setError(null)
    abort.current = new AbortController()
    setProgress('working')
    const request: ReportRequest = {
      kind,
      format,
      ...(choice.uses.range ? { from: range.from, to: range.to } : {}),
      ...(choice.uses.member ? { memberId } : {}),
      ...(choice.uses.asOf ? { asOf } : {}),
    }
    try {
      const file = await downloadReport(
        request,
        (percent) => setProgress(percent ?? 'working'),
        abort.current.signal,
      )
      downloadBlob(file.filename, file.blob)
      notify.success(`${file.filename} downloaded`)
    } catch (caught) {
      const failure = toApiError(caught)
      if (failure.code === 'REQUEST_CANCELLED') {
        setError(null)
      } else if (isApiError(failure) && failure.code === 'PLAN_FEATURE_UNAVAILABLE') {
        if (format === 'PDF') setPdfRefused(true)
        setError(failure.detail)
      } else {
        setError(failure.message)
      }
    } finally {
      setProgress(null)
      abort.current = null
    }
  }

  return (
    <div className="grid max-w-2xl gap-5">
      <div className="grid gap-1.5">
        <Label htmlFor="report-kind">Report</Label>
        <SimpleSelect
          id="report-kind"
          options={REPORTS.map((report) => ({ value: report.kind, label: report.label }))}
          value={kind}
          onValueChange={(value) => setKind(value as ReportKind)}
          disabled={busy}
        />
        <p className="text-sm text-muted">{choice?.about}</p>
      </div>

      <fieldset className="grid gap-2" disabled={busy}>
        <legend className="mb-1 text-sm font-medium">Format</legend>
        <RadioGroup
          value={format}
          onValueChange={(value) => setFormat(value as ReportFormat)}
          aria-label="Format"
          className="grid-cols-2"
        >
          <RadioGroupItem
            value="CSV"
            label="CSV, for a spreadsheet"
            disabled={loadedPlan && !csvIncluded}
          />
          <RadioGroupItem
            value="PDF"
            label="PDF, to print or share"
            disabled={loadedPlan && !pdfIncluded}
          />
        </RadioGroup>
      </fieldset>

      {loadedPlan && (!pdfIncluded || !csvIncluded) ? (
        <div
          role="note"
          className="flex items-start gap-3 rounded border border-gold-500 bg-gold-50 p-4 text-sm text-brand-900"
        >
          <Lock aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <p>
            <strong>
              {!pdfIncluded && !csvIncluded
                ? `Reports are not part of the ${plan.data?.planName} plan.`
                : !pdfIncluded
                  ? `PDF reports are not part of the ${plan.data?.planName} plan.`
                  : `CSV export is not part of the ${plan.data?.planName} plan.`}
            </strong>{' '}
            <Link to={paths.admin.support} className="font-medium text-brand-700 underline">
              Ask about upgrading
            </Link>{' '}
            to use {!pdfIncluded ? 'PDF' : 'CSV'}.
          </p>
        </div>
      ) : null}

      {choice?.uses.range ? (
        <fieldset className="grid gap-3" disabled={busy}>
          <legend className="mb-1 text-sm font-medium">Period</legend>
          <SimpleSelect
            aria-label="Period"
            options={[
              { value: 'month', label: 'This month' },
              { value: 'quarter', label: 'This quarter' },
              { value: 'year', label: 'This financial year' },
              { value: 'custom', label: 'Choose the days' },
            ]}
            value={preset}
            onValueChange={(value) => setPreset(value as Preset)}
          />
          {preset === 'custom' ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1.5">
                <Label htmlFor="report-from">From</Label>
                <DatePicker
                  id="report-from"
                  value={custom.from || undefined}
                  onValueChange={(value) => setCustom({ ...custom, from: value ?? '' })}
                  {...(custom.to ? { max: custom.to } : {})}
                />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="report-to">To</Label>
                <DatePicker
                  id="report-to"
                  value={custom.to || undefined}
                  onValueChange={(value) => setCustom({ ...custom, to: value ?? '' })}
                  {...(custom.from ? { min: custom.from } : {})}
                />
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted">
              {range.from} to {range.to}
            </p>
          )}
        </fieldset>
      ) : null}

      {choice?.uses.member ? (
        <div className="grid gap-1.5">
          <Label htmlFor="report-member">Member</Label>
          <MemberPicker
            id="report-member"
            value={memberId}
            onChange={setMemberId}
            disabled={busy}
          />
        </div>
      ) : null}

      {choice?.uses.asOf ? (
        <div className="grid gap-1.5">
          <Label htmlFor="report-asof">As of</Label>
          <DatePicker
            id="report-asof"
            value={asOf}
            onValueChange={(value) => setAsOf(value ?? today)}
            max={today}
          />
        </div>
      ) : null}

      {error ? (
        <p
          role="alert"
          className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
        >
          {error}
        </p>
      ) : null}
      {missing ? <p className="text-sm text-muted">{missing}</p> : null}

      {busy ? (
        <div className="grid gap-2">
          <div
            role="progressbar"
            aria-label="Downloading the report"
            aria-valuemin={0}
            aria-valuemax={100}
            {...(typeof progress === 'number' ? { 'aria-valuenow': progress } : {})}
            className="h-2 overflow-hidden rounded bg-brand-50"
          >
            <div
              className={
                typeof progress === 'number'
                  ? 'h-full bg-brand-600'
                  : 'h-full w-1/3 animate-pulse bg-brand-600'
              }
              style={typeof progress === 'number' ? { width: `${progress}%` } : undefined}
            />
          </div>
          <p className="text-sm text-muted" role="status">
            {typeof progress === 'number'
              ? `Downloading, ${progress}%`
              : 'Preparing the report. Large reports take a moment.'}
          </p>
        </div>
      ) : null}

      <div className="flex gap-2">
        <Button
          onClick={() => void download()}
          loading={busy}
          disabled={missing !== null || (loadedPlan && !formatAllowed)}
        >
          <Download aria-hidden="true" className="size-4" />
          Download
        </Button>
        {busy ? (
          <Button variant="secondary" onClick={() => abort.current?.abort()}>
            Cancel
          </Button>
        ) : null}
      </div>
    </div>
  )
}
