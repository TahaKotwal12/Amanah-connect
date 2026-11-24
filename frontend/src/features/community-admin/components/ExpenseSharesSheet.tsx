import { useMemo, useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { addDaysToDate, formatDate, todayInIst } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Input } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/shared/components/ui/radio-group'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { EntryView, InvoiceView, ShareView } from '@/types'
import { fetchInvoice } from '../api/billing'
import { useDivideExpense, useExpenseShares } from '../hooks/useExpenseShares'
import { useMembers } from '../hooks/useMembers'
import { useWriteAccess } from '../hooks/usePlan'
import { splitPreview } from '../splitPreview'
import { InvoiceActionDialogs, type InvoiceActionTarget } from './InvoiceActionDialogs'

interface ExpenseSharesSheetProps {
  /** The expense to collect from members, or null when the panel is closed. */
  expense: EntryView | null
  onOpenChange: (open: boolean) => void
  currency: string
}

const PICKER_SIZE = 100

/**
 * Collect an expense back from members. If the expense has not been divided, this is the form that
 * divides it (everyone, or chosen members) and emails each member their bill. Once divided it shows who
 * has paid, and lets the admin record cash on a share (which issues and emails the receipt) or cancel an
 * unpaid one.
 */
export function ExpenseSharesSheet({ expense, onOpenChange, currency }: ExpenseSharesSheetProps) {
  return (
    <Sheet open={expense !== null} onOpenChange={onOpenChange}>
      <SheetContent className="max-w-xl">
        {expense ? (
          <Body
            key={expense.id}
            expense={expense}
            currency={currency}
            onClose={() => onOpenChange(false)}
          />
        ) : null}
      </SheetContent>
    </Sheet>
  )
}

function Body({
  expense,
  currency,
  onClose,
}: {
  expense: EntryView
  currency: string
  onClose: () => void
}) {
  const shares = useExpenseShares(expense.id)
  const live = (shares.data?.shares ?? []).filter((share) => share.status !== 'CANCELLED')

  return (
    <>
      <SheetHeader>
        <SheetTitle>Collect from members</SheetTitle>
        <SheetDescription>
          {expense.title}, {formatMoney(expense.amount, { currency })}
        </SheetDescription>
      </SheetHeader>
      {shares.isLoading ? (
        <Skeleton className="h-48" role="status" aria-label="Loading" />
      ) : shares.isError ? (
        <ErrorState error={shares.error} onRetry={() => void shares.refetch()} />
      ) : live.length > 0 && shares.data ? (
        <Progress data={shares.data} currency={currency} />
      ) : (
        <DivideForm expense={expense} currency={currency} onClose={onClose} />
      )}
    </>
  )
}

// ---- before it is divided ---------------------------------------------------------------------

function DivideForm({
  expense,
  currency,
  onClose,
}: {
  expense: EntryView
  currency: string
  onClose: () => void
}) {
  const divide = useDivideExpense()
  const write = useWriteAccess()
  const members = useMembers({ size: PICKER_SIZE, sort: ['name,asc'], status: 'ACTIVE' })
  const everyone = members.data?.total ?? 0
  const [audience, setAudience] = useState<'all' | 'some'>('all')
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set())
  const [search, setSearch] = useState('')
  const [dueDate, setDueDate] = useState(() => addDaysToDate(todayInIst(), 15))
  const [sendEmails, setSendEmails] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const count = audience === 'all' ? everyone : chosen.size
  const preview = splitPreview(expense.amount, count)
  const visible = useMemo(() => {
    const needle = search.trim().toLowerCase()
    const items = members.data?.items ?? []
    return needle
      ? items.filter(
          (member) =>
            member.fullName.toLowerCase().includes(needle) ||
            member.memberNo.toLowerCase().includes(needle),
        )
      : items
  }, [members.data, search])

  function toggle(id: string, on: boolean) {
    setChosen((previous) => {
      const next = new Set(previous)
      if (on) next.add(id)
      else next.delete(id)
      return next
    })
  }

  async function submit() {
    setError(null)
    try {
      const result = await divide.mutateAsync({
        id: expense.id,
        body: {
          dueDate,
          sendEmails,
          ...(audience === 'some' ? { memberIds: [...chosen] } : {}),
        },
      })
      const skipped =
        result.emailsSkippedNoAddress + result.emailsSkippedNoConsent + result.emailsSkippedQuota
      notify.success(
        `Bills made for ${result.members} members` +
          (sendEmails
            ? `, ${result.emailsQueued} emailed${skipped > 0 ? `, ${skipped} not emailed` : ''}`
            : ''),
      )
    } catch (failure) {
      setError(toApiError(failure).message)
    }
  }

  return (
    <form
      noValidate
      className="grid gap-5"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      <p className="text-sm text-muted">
        The amount is divided equally. Each member gets a bill by email, and you record the cash
        when they pay, which sends their receipt.
      </p>

      <fieldset className="grid gap-1">
        <legend className="mb-1 text-sm font-medium text-ink">Who pays</legend>
        <RadioGroup
          value={audience}
          onValueChange={(value) => setAudience(value === 'some' ? 'some' : 'all')}
          aria-label="Who pays"
        >
          <RadioGroupItem
            value="all"
            label={`All active members${members.data ? ` (${everyone})` : ''}`}
          />
          <RadioGroupItem value="some" label="Chosen members" />
        </RadioGroup>
      </fieldset>

      {audience === 'some' ? (
        <div className="grid gap-2">
          <Label htmlFor="share-search">Find a member</Label>
          <Input
            id="share-search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Name or member number"
          />
          <ul
            aria-label="Members"
            className="max-h-64 divide-y divide-brand-100 overflow-y-auto rounded border border-brand-100"
          >
            {visible.map((member) => (
              <li key={member.id} className="px-3">
                <Checkbox
                  label={`${member.fullName} (${member.memberNo})`}
                  checked={chosen.has(member.id)}
                  onCheckedChange={(checked) => toggle(member.id, checked === true)}
                />
              </li>
            ))}
            {visible.length === 0 ? (
              <li className="px-3 py-3 text-sm text-muted">
                {members.isLoading ? 'Loading members' : 'No member matches'}
              </li>
            ) : null}
          </ul>
          <p className="text-sm text-muted">{chosen.size} chosen</p>
        </div>
      ) : null}

      <div className="grid gap-1.5">
        <Label htmlFor="share-due">Pay by</Label>
        <DatePicker
          id="share-due"
          value={dueDate}
          onValueChange={(value) => value && setDueDate(value)}
          min={todayInIst()}
        />
      </div>

      <Checkbox
        label="Email each member their bill"
        description="Only members with an email address who agreed to emails are emailed."
        checked={sendEmails}
        onCheckedChange={(checked) => setSendEmails(checked === true)}
      />

      <p
        role="status"
        aria-live="polite"
        className="rounded bg-brand-50 px-3 py-2 text-sm text-brand-900"
      >
        {preview
          ? preview.low === preview.high
            ? `${count} members pay ${formatMoney(preview.low, { currency })} each.`
            : `${count} members pay ${formatMoney(preview.low, { currency })} or ${formatMoney(preview.high, { currency })}, adding up to exactly ${formatMoney(expense.amount, { currency })}.`
          : count === 0
            ? 'Choose who pays.'
            : 'That amount is too small to divide among so many members.'}
      </p>

      {error ? (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      ) : null}

      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onClose} disabled={divide.isPending}>
          Cancel
        </Button>
        <Button
          type="submit"
          loading={divide.isPending}
          disabled={!write.allowed || preview === null}
          {...(write.reason ? { title: write.reason } : {})}
        >
          Divide and send bills
        </Button>
      </div>
    </form>
  )
}

// ---- after it is divided ----------------------------------------------------------------------

function Progress({
  data,
  currency,
}: {
  data: NonNullable<ReturnType<typeof useExpenseShares>['data']>
  currency: string
}) {
  const write = useWriteAccess()
  const [action, setAction] = useState<InvoiceActionTarget | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  async function open(share: ShareView, kind: 'pay' | 'cancel') {
    setBusy(share.invoiceId)
    try {
      const detail = await fetchInvoice(share.invoiceId)
      const invoice: InvoiceView = detail.invoice
      setAction({ kind, invoice })
    } catch (failure) {
      notify.apiError(failure)
    } finally {
      setBusy(null)
    }
  }

  const money = (amount: string) => formatMoney(amount, { currency })
  const canPay = (share: ShareView) =>
    share.status === 'ISSUED' || share.status === 'PARTIAL' || share.status === 'OVERDUE'

  return (
    <div className="grid gap-5">
      <dl className="grid grid-cols-3 gap-3 text-center">
        {[
          ['Divided', data.divided],
          ['Collected', data.collected],
          ['Still to collect', data.pending],
        ].map(([label, value]) => (
          <div key={label} className="rounded border border-brand-100 bg-white px-2 py-3">
            <dt className="text-xs text-muted">{label}</dt>
            <dd className="font-heading text-lg font-semibold tabular-nums text-brand-900">
              {money(value as string)}
            </dd>
          </div>
        ))}
      </dl>

      <ul aria-label="Shares" className="grid gap-2">
        {data.shares.map((share) => (
          <li
            key={share.invoiceId}
            className="grid gap-2 rounded border border-brand-100 bg-white p-3 sm:grid-cols-[1fr_auto] sm:items-center"
          >
            <div className="min-w-0">
              <p className="truncate font-medium text-ink">
                {share.memberName} <span className="text-muted">({share.memberNo})</span>
              </p>
              <p className="text-sm tabular-nums text-muted">
                {share.invoiceNo}, {money(share.amount)}
                {share.status === 'PARTIAL' || share.status === 'PAID'
                  ? `, paid ${money(share.amountPaid)}`
                  : `, due ${formatDate(share.dueDate)}`}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2 sm:justify-end">
              <StatusBadge status={share.status} />
              {canPay(share) ? (
                <>
                  <Button
                    size="sm"
                    disabled={!write.allowed || busy === share.invoiceId}
                    onClick={() => void open(share, 'pay')}
                    aria-label={`Record cash from ${share.memberName}`}
                  >
                    Record cash
                  </Button>
                  {share.amountPaid === '0.00' ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={!write.allowed || busy === share.invoiceId}
                      onClick={() => void open(share, 'cancel')}
                      aria-label={`Cancel the share of ${share.memberName}`}
                    >
                      Cancel
                    </Button>
                  ) : null}
                </>
              ) : null}
            </div>
          </li>
        ))}
      </ul>

      <InvoiceActionDialogs target={action} onClose={() => setAction(null)} currency={currency} />
    </div>
  )
}
