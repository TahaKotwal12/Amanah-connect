import { Paperclip, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useWatch } from 'react-hook-form'
import { isApiError, toApiError } from '@/lib/apiError'
import { todayInIst } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { formatBytes, uploadToPresignedUrl, validateFile } from '@/lib/upload'
import { Button } from '@/shared/components/ui/button'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input, Textarea } from '@/shared/components/ui/input'
import { MoneyInput } from '@/shared/components/ui/money-input'
import { RadioGroup, RadioGroupItem } from '@/shared/components/ui/radio-group'
import { SimpleSelect } from '@/shared/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import type { EntryView, LedgerType } from '@/types'
import { requestEntryAttachmentUpload } from '../api/ledger'
import {
  LEDGER_TYPES,
  categoriesFor,
  emptyEntry,
  entrySchema,
  entryToForm,
  toCreateEntryRequest,
  toUpdateEntryRequest,
  type EntryInput,
  type EntryValues,
} from '../budgetSchemas'
import { useCategories, useCreateEntry, useUpdateEntry } from '../hooks/useLedger'

const ATTACHMENT_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'application/pdf'] as const
const FIELDS = ['type', 'categoryId', 'amount', 'entryDate', 'title', 'notes', 'attachmentKey']

interface EntrySheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The type a new entry starts as. */
  initialType?: LedgerType
  /** The manual entry being edited; undefined adds a new one. */
  entry?: EntryView | undefined
}

/** Add an income or an expense, with a receipt image or PDF if you have one. */
export function EntrySheet({
  open,
  onOpenChange,
  initialType = 'EXPENSE',
  entry,
}: EntrySheetProps) {
  const create = useCreateEntry()
  const update = useUpdateEntry()
  const categories = useCategories()
  const original = entry ? entryToForm(entry) : emptyEntry(initialType)
  const form = useZodForm(entrySchema, { defaultValues: original })
  const [formError, setFormError] = useState<string | null>(null)
  /** The file chosen but not yet saved with the entry (it is uploaded on save). */
  const [file, setFile] = useState<File | null>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const editing = entry !== undefined

  useEffect(() => {
    if (open) form.reset(entry ? entryToForm(entry) : emptyEntry(initialType))
  }, [open, entry, initialType, form])

  const watched = useWatch({ control: form.control }) as EntryInput
  const options = useMemo(
    () =>
      categoriesFor(categories.data ?? [], watched.type === 'INCOME' ? 'INCOME' : 'EXPENSE').map(
        (category) => ({ value: category.id, label: category.name }),
      ),
    [categories.data, watched.type],
  )
  const busy = create.isPending || update.isPending || progress !== null

  function close(next: boolean) {
    if (busy) return
    if (!next) {
      setFormError(null)
      setFile(null)
      setFileError(null)
    }
    onOpenChange(next)
  }

  function choose(chosen: File | undefined) {
    setFileError(null)
    if (!chosen) return
    const problem = validateFile(chosen, { accept: ATTACHMENT_TYPES })
    if (problem) {
      setFileError(problem)
      return
    }
    setFile(chosen)
  }

  async function submit(values: EntryValues) {
    setFormError(null)
    try {
      let attachmentKey = values.attachmentKey
      if (file) {
        setProgress(0)
        const instructions = await requestEntryAttachmentUpload(file)
        if (file.size > instructions.maxBytes) {
          throw new Error(
            `${file.name} is ${formatBytes(file.size)}; the limit is ${formatBytes(instructions.maxBytes)}.`,
          )
        }
        await uploadToPresignedUrl(instructions, file, { onProgress: setProgress })
        attachmentKey = instructions.attachmentKey
      }
      const final = { ...values, attachmentKey }
      if (entry) {
        const body = toUpdateEntryRequest(final, entryToForm(entry))
        if (Object.keys(body).length === 0) {
          notify.info('Nothing to save: no field was changed')
          return
        }
        await update.mutateAsync({ id: entry.id, body })
        notify.success('Entry saved')
      } else {
        await create.mutateAsync(toCreateEntryRequest(final))
        notify.success(final.type === 'INCOME' ? 'Income added' : 'Expense added')
      }
      setFile(null)
      setFormError(null)
      // Not close(): the mutation is still pending in this render, and close() ignores that.
      onOpenChange(false)
    } catch (error) {
      if (isApiError(error) && error.code === 'PLAN_LIMIT_EXCEEDED') {
        onOpenChange(false)
        return
      }
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(
        rest.length > 0
          ? rest.join(' ')
          : error instanceof Error && !isApiError(error)
            ? error.message
            : toApiError(error).message,
      )
    } finally {
      setProgress(null)
    }
  }

  const keptAttachment = !file && watched.attachmentKey !== '' ? entry?.attachmentUrl : null

  return (
    <Sheet open={open} onOpenChange={close}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>
            {editing ? 'Edit entry' : watched.type === 'INCOME' ? 'Add income' : 'Add an expense'}
          </SheetTitle>
          <SheetDescription>
            {editing
              ? 'The amount and whether it is income or expense never change. To fix them, reverse the entry and add the right one.'
              : 'Money that came in or went out outside of member payments.'}
          </SheetDescription>
        </SheetHeader>
        <Form {...form}>
          <form
            noValidate
            onSubmit={(event) => {
              void form.handleSubmit(submit)(event)
            }}
            className="grid gap-4"
          >
            {formError ? (
              <p
                role="alert"
                className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
              >
                {formError}
              </p>
            ) : null}

            <FormField control={form.control} name="type" label="Type" hideLabel asGroup>
              {({ field }) => (
                <RadioGroup
                  value={field.value}
                  onValueChange={(value) => {
                    field.onChange(value)
                    form.setValue('categoryId', '')
                  }}
                  aria-label="Type"
                  className="grid-cols-2"
                  disabled={editing}
                >
                  {LEDGER_TYPES.map((option) => (
                    <RadioGroupItem key={option.value} value={option.value} label={option.label} />
                  ))}
                </RadioGroup>
              )}
            </FormField>
            <FormField control={form.control} name="categoryId" label="Category" required>
              {({ field, control }) => (
                <SimpleSelect
                  id={control.id}
                  aria-invalid={control['aria-invalid']}
                  aria-describedby={control['aria-describedby']}
                  options={options}
                  value={field.value}
                  onValueChange={field.onChange}
                  placeholder={categories.isLoading ? 'Loading categories' : 'Choose a category'}
                />
              )}
            </FormField>
            <FormField control={form.control} name="title" label="Title" required>
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField control={form.control} name="amount" label="Amount" required>
                {({ field, control }) => (
                  <MoneyInput
                    id={control.id}
                    name={control.name}
                    aria-invalid={control['aria-invalid']}
                    aria-describedby={control['aria-describedby']}
                    aria-required={control['aria-required']}
                    value={field.value}
                    onValueChange={field.onChange}
                    onBlur={field.onBlur}
                    disabled={editing}
                    symbol="₹"
                  />
                )}
              </FormField>
              <FormField control={form.control} name="entryDate" label="Date" required>
                {({ field, control }) => (
                  <DatePicker
                    id={control.id}
                    aria-invalid={control['aria-invalid']}
                    aria-describedby={control['aria-describedby']}
                    value={field.value || undefined}
                    onValueChange={(value) => field.onChange(value ?? '')}
                    onBlur={field.onBlur}
                    max={todayInIst()}
                  />
                )}
              </FormField>
            </div>
            <FormField control={form.control} name="notes" label="Notes">
              {({ field, control }) => <Textarea {...field} {...control} rows={3} />}
            </FormField>

            <div className="grid gap-1.5">
              <p className="text-sm font-medium">Receipt or bill</p>
              <input
                ref={fileInput}
                type="file"
                accept={ATTACHMENT_TYPES.join(',')}
                aria-label="Attach a receipt or bill"
                className="sr-only"
                disabled={busy}
                onChange={(event) => choose(event.target.files?.[0])}
              />
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  variant="secondary"
                  disabled={busy}
                  onClick={() => fileInput.current?.click()}
                >
                  <Paperclip aria-hidden="true" className="size-4" />
                  {file || keptAttachment ? 'Choose another file' : 'Attach a file'}
                </Button>
                {file ? (
                  <span className="flex min-w-0 items-center gap-1 text-sm">
                    <span className="truncate">
                      {file.name} ({formatBytes(file.size)})
                    </span>
                    <button
                      type="button"
                      aria-label={`Remove ${file.name}`}
                      disabled={busy}
                      onClick={() => {
                        setFile(null)
                        if (fileInput.current) fileInput.current.value = ''
                      }}
                      className="flex size-8 shrink-0 items-center justify-center rounded hover:bg-brand-50"
                    >
                      <X aria-hidden="true" className="size-4" />
                    </button>
                  </span>
                ) : keptAttachment ? (
                  <span className="flex items-center gap-2 text-sm">
                    <a
                      href={keptAttachment}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-brand-700 underline"
                    >
                      Current attachment
                    </a>
                    <button
                      type="button"
                      className="underline"
                      onClick={() => form.setValue('attachmentKey', '', { shouldDirty: true })}
                    >
                      Remove
                    </button>
                  </span>
                ) : null}
              </div>
              <p className="text-xs text-muted">PNG, JPEG, WebP or PDF.</p>
              {fileError ? (
                <p role="alert" className="text-sm text-danger">
                  {fileError}
                </p>
              ) : null}
            </div>

            <div className="flex justify-end gap-2 border-t border-brand-100 pt-4">
              <Button
                type="button"
                variant="secondary"
                onClick={() => close(false)}
                disabled={busy}
              >
                Cancel
              </Button>
              <Button type="submit" loading={busy}>
                {progress !== null && progress < 100
                  ? `Uploading ${progress}%`
                  : editing
                    ? 'Save changes'
                    : watched.type === 'INCOME'
                      ? 'Add income'
                      : 'Add expense'}
              </Button>
            </div>
          </form>
        </Form>
      </SheetContent>
    </Sheet>
  )
}
