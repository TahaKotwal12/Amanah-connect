import { Paperclip, Plus } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { toApiError } from '@/lib/apiError'
import { formatDate } from '@/lib/dates'
import { formatMoney } from '@/lib/money'
import { notify } from '@/lib/notify'
import { paths } from '@/routes/paths'
import { Badge } from '@/shared/components/ui/badge'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { DataTable, createDataTableColumns } from '@/shared/components/ui/data-table'
import { DropdownMenuItem } from '@/shared/components/ui/dropdown-menu'
import { EmptyState } from '@/shared/components/ui/empty-state'
import { ErrorState } from '@/shared/components/ui/error-state'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import { ReasonDialog } from '@/shared/components/ui/reason-dialog'
import { SimpleSelect } from '@/shared/components/ui/select'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import { useListState } from '@/shared/hooks/useListState'
import type { CategoryView, EntryView, LedgerType } from '@/types'
import { fetchPayment } from '../api/billing'
import { LEDGER_TYPES, categorySchema, type CategoryInput } from '../budgetSchemas'
import {
  useCategories,
  useCreateCategory,
  useEntries,
  useReverseEntry,
  useUpdateCategory,
} from '../hooks/useLedger'
import { useWriteAccess } from '../hooks/usePlan'
import type { IsoRange } from '../dateRanges'
import { EntrySheet } from './EntrySheet'

const ALL = 'all'
const typeLabel = (type: LedgerType) => (type === 'INCOME' ? 'Income' : 'Expense')

// ---- the ledger -------------------------------------------------------------------------------

const col = createDataTableColumns<EntryView>()

interface LedgerTabProps {
  range: IsoRange
  currency: string
  /** Opens the add panel from outside (the dashboard's "Add expense" goes here). */
  addType: LedgerType | null
  onAddClosed: () => void
}

export function LedgerTab({ range, currency, addType, onAddClosed }: LedgerTabProps) {
  const list = useListState(['type', 'categoryId', 'q'] as const, ['entryDate,desc'])
  const { type, categoryId, q } = list.filters
  const write = useWriteAccess()
  const navigate = useNavigate()
  const categories = useCategories()
  const query = useEntries({
    page: list.pagination.pageIndex,
    size: list.pagination.pageSize,
    from: range.from,
    to: range.to,
    ...(type ? { type: type as LedgerType } : {}),
    ...(categoryId ? { categoryId } : {}),
    ...(q ? { q } : {}),
  })
  const reverse = useReverseEntry()
  const [adding, setAdding] = useState<LedgerType | null>(null)
  const [editing, setEditing] = useState<EntryView | null>(null)
  const [reversing, setReversing] = useState<EntryView | null>(null)

  async function openPayment(entry: EntryView) {
    if (!entry.sourceId) return
    try {
      const payment = await fetchPayment(entry.sourceId)
      void navigate(
        payment.invoiceId
          ? paths.admin.invoice(payment.invoiceId)
          : `${paths.admin.billing}?tab=receipts`,
      )
    } catch (error) {
      notify.apiError(error)
    }
  }

  const columns = useMemo(
    () =>
      col.columns([
        col.accessor('entryDate', {
          id: 'entryDate',
          header: 'Date',
          cell: (info) => formatDate(info.getValue()),
        }),
        col.accessor('title', {
          id: 'title',
          header: 'Title',
          cell: (info) => {
            const row = info.row.original
            return (
              <div className="min-w-0">
                <p className={row.reversed ? 'text-muted line-through' : undefined}>
                  {info.getValue()}
                </p>
                {row.reversalReason ? (
                  <p className="text-xs text-muted">Reversed: {row.reversalReason}</p>
                ) : null}
                {row.attachmentUrl ? (
                  <a
                    href={row.attachmentUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-xs text-brand-700 underline"
                  >
                    <Paperclip aria-hidden="true" className="size-3" />
                    Attachment
                  </a>
                ) : null}
              </div>
            )
          },
        }),
        col.accessor((row) => row.category.name, {
          id: 'category',
          header: 'Category',
          enableSorting: false,
        }),
        col.accessor('type', {
          header: 'Type',
          enableSorting: false,
          cell: (info) => (
            <Badge variant={info.getValue() === 'INCOME' ? 'success' : 'warning'}>
              {typeLabel(info.getValue())}
            </Badge>
          ),
        }),
        col.accessor('source', {
          header: 'From',
          enableSorting: false,
          cell: (info) => {
            const row = info.row.original
            if (row.reversedOf) return <StatusBadge status="reversed" label="Reversal" />
            return info.getValue() === 'PAYMENT' ? (
              <Badge variant="brand">A payment</Badge>
            ) : (
              <Badge>Entered by hand</Badge>
            )
          },
        }),
        col.accessor('amount', {
          id: 'amount',
          header: 'Amount',
          cell: (info) => (
            <span className="tabular-nums">{formatMoney(info.getValue(), { currency })}</span>
          ),
        }),
      ]),
    [currency],
  )

  const filtering = type !== '' || categoryId !== '' || q !== ''
  const categoryOptions = [
    { value: ALL, label: 'Any category' },
    ...(categories.data ?? []).map((category) => ({ value: category.id, label: category.name })),
  ]
  const sheetType = adding ?? addType

  return (
    <>
      <div className="mb-4 flex flex-wrap gap-2">
        <Button
          onClick={() => setAdding('INCOME')}
          disabled={!write.allowed}
          {...(write.reason ? { title: write.reason } : {})}
        >
          <Plus aria-hidden="true" className="size-4" />
          Add income
        </Button>
        <Button variant="secondary" onClick={() => setAdding('EXPENSE')} disabled={!write.allowed}>
          <Plus aria-hidden="true" className="size-4" />
          Add expense
        </Button>
      </div>

      <DataTable
        label="Ledger"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(row) => row.id}
        rowCount={query.data?.total ?? 0}
        pagination={list.pagination}
        onPaginationChange={list.setPagination}
        sorting={list.sorting}
        onSortingChange={list.setSorting}
        isLoading={query.isLoading}
        isFetching={query.isFetching}
        error={query.error}
        onRetry={() => void query.refetch()}
        search={{
          value: q,
          onSearch: (value) => list.setFilter('q', value),
          placeholder: 'Search by title',
          label: 'Search the ledger',
        }}
        filters={
          <>
            <SimpleSelect
              aria-label="Filter by type"
              options={[{ value: ALL, label: 'Income and expense' }, ...LEDGER_TYPES]}
              value={type || ALL}
              onValueChange={(value) => list.setFilter('type', value === ALL ? '' : value)}
            />
            <SimpleSelect
              aria-label="Filter by category"
              options={categoryOptions}
              value={categoryId || ALL}
              onValueChange={(value) => list.setFilter('categoryId', value === ALL ? '' : value)}
            />
          </>
        }
        rowLabel={(row) => row.title}
        rowActions={(row) =>
          row.readOnly ? (
            row.sourceId ? (
              <DropdownMenuItem onSelect={() => void openPayment(row)}>
                View the payment
              </DropdownMenuItem>
            ) : (
              <DropdownMenuItem disabled>Cannot be changed</DropdownMenuItem>
            )
          ) : (
            <>
              <DropdownMenuItem disabled={!write.allowed} onSelect={() => setEditing(row)}>
                Edit
              </DropdownMenuItem>
              {row.reversed ? (
                <DropdownMenuItem disabled>Already reversed</DropdownMenuItem>
              ) : (
                <DropdownMenuItem
                  destructive
                  disabled={!write.allowed}
                  onSelect={() => setReversing(row)}
                >
                  Reverse
                </DropdownMenuItem>
              )}
            </>
          )
        }
        emptyState={
          filtering ? (
            <EmptyState
              title="No entries match"
              description="Try another type or category, or clear the filters."
            />
          ) : (
            <EmptyState
              title="Nothing in this period"
              description="Payments recorded against invoices appear here by themselves. Add other income and expenses by hand."
            />
          )
        }
      />

      <EntrySheet
        open={sheetType !== null}
        onOpenChange={(open) => {
          if (!open) {
            setAdding(null)
            onAddClosed()
          }
        }}
        initialType={sheetType ?? 'EXPENSE'}
      />
      <EntrySheet
        open={editing !== null}
        onOpenChange={(open) => !open && setEditing(null)}
        entry={editing ?? undefined}
      />
      <ReasonDialog
        open={reversing !== null}
        onOpenChange={(open) => !open && setReversing(null)}
        title={`Reverse "${reversing?.title ?? 'this entry'}"?`}
        description="Nothing is deleted. A matching negative entry is added so the totals come right, and both stay in the ledger. Then add the correct entry if there should be one."
        confirmLabel="Reverse entry"
        onSubmit={async (reason) => {
          if (!reversing) return
          await reverse.mutateAsync({ id: reversing.id, body: { reason } })
          notify.success('The entry was reversed')
        }}
      />
    </>
  )
}

// ---- categories -------------------------------------------------------------------------------

function CategoryRow({ category }: { category: CategoryView }) {
  const update = useUpdateCategory()
  const write = useWriteAccess()
  const [renaming, setRenaming] = useState(false)
  const [name, setName] = useState(category.name)
  const [error, setError] = useState<string | null>(null)

  async function save() {
    setError(null)
    try {
      await update.mutateAsync({ id: category.id, body: { name: name.trim() } })
      setRenaming(false)
      notify.success('Category renamed')
    } catch (caught) {
      setError(toApiError(caught).message)
    }
  }

  return (
    <li className="flex flex-wrap items-center justify-between gap-3 py-3">
      <div className="min-w-0">
        {renaming ? (
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor={`rename-${category.id}`} className="sr-only">
              Name of the category
            </label>
            <Input
              id={`rename-${category.id}`}
              value={name}
              onChange={(event) => setName(event.target.value)}
              className="w-64"
              aria-invalid={error !== null}
            />
            <Button
              size="sm"
              onClick={() => void save()}
              loading={update.isPending}
              disabled={name.trim() === ''}
            >
              Save
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setRenaming(false)
                setName(category.name)
                setError(null)
              }}
            >
              Cancel
            </Button>
            {error ? (
              <p role="alert" className="basis-full text-sm text-danger">
                {error}
              </p>
            ) : null}
          </div>
        ) : (
          <p
            className={
              category.active ? 'font-medium text-brand-900' : 'font-medium text-muted line-through'
            }
          >
            {category.name}
          </p>
        )}
        <p className="text-sm text-muted">
          {typeLabel(category.type)}, {category.entryCount.toLocaleString('en-IN')}{' '}
          {category.entryCount === 1 ? 'entry' : 'entries'}
          {category.system ? ', used by payments' : ''}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        {!renaming ? (
          <Button
            size="sm"
            variant="secondary"
            disabled={!write.allowed}
            onClick={() => setRenaming(true)}
            aria-label={`Rename ${category.name}`}
          >
            Rename
          </Button>
        ) : null}
        <Checkbox
          label="In use"
          checked={category.active}
          disabled={!write.allowed || category.system}
          onCheckedChange={(checked) => {
            void update
              .mutateAsync({ id: category.id, body: { active: checked === true } })
              .catch((caught: unknown) => notify.apiError(caught))
          }}
          aria-label={`${category.name} is in use`}
        />
      </div>
    </li>
  )
}

export function CategoriesTab() {
  const categories = useCategories()
  const create = useCreateCategory()
  const write = useWriteAccess()
  const form = useZodForm(categorySchema, {
    defaultValues: { name: '', type: 'EXPENSE' } satisfies CategoryInput,
  })
  const [formError, setFormError] = useState<string | null>(null)
  const formRef = useRef<HTMLFormElement>(null)

  async function add(values: { name: string; type: LedgerType }) {
    setFormError(null)
    try {
      await create.mutateAsync(values)
      notify.success(`${values.name} added`)
      form.reset({ name: '', type: values.type })
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, ['name', 'type'])
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  const groups: LedgerType[] = ['INCOME', 'EXPENSE']

  return (
    <div className="grid gap-6">
      <Form {...form}>
        <form
          ref={formRef}
          noValidate
          onSubmit={(event) => {
            void form.handleSubmit(add)(event)
          }}
          className="flex flex-wrap items-start gap-3 rounded-card border border-brand-100 bg-white p-4"
        >
          <FormField
            control={form.control}
            name="name"
            label="New category"
            required
            className="min-w-56 flex-1"
          >
            {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
          </FormField>
          <FormField control={form.control} name="type" label="Type" required>
            {({ field, control }) => (
              <SimpleSelect
                id={control.id}
                options={LEDGER_TYPES}
                value={field.value}
                onValueChange={field.onChange}
                className="w-40"
              />
            )}
          </FormField>
          <div className="pt-7">
            <Button type="submit" loading={create.isPending} disabled={!write.allowed}>
              Add category
            </Button>
          </div>
          {formError ? (
            <p role="alert" className="basis-full text-sm text-danger">
              {formError}
            </p>
          ) : null}
        </form>
      </Form>

      {categories.isError ? (
        <ErrorState
          headingLevel={3}
          error={categories.error}
          onRetry={() => void categories.refetch()}
          retrying={categories.isFetching}
        />
      ) : categories.isLoading ? (
        <Skeleton className="h-48" role="status" aria-label="Loading categories" />
      ) : (
        groups.map((group) => {
          const rows = (categories.data ?? []).filter((category) => category.type === group)
          return (
            <section key={group} aria-label={`${typeLabel(group)} categories`}>
              <h2 className="mb-1 font-heading text-lg font-semibold text-brand-900">
                {typeLabel(group)} categories
              </h2>
              {rows.length === 0 ? (
                <p className="text-sm text-muted">None yet.</p>
              ) : (
                <ul className="divide-y divide-brand-100 rounded-card border border-brand-100 bg-white px-4">
                  {rows.map((category) => (
                    <CategoryRow key={category.id} category={category} />
                  ))}
                </ul>
              )}
            </section>
          )
        })
      )}
    </div>
  )
}
