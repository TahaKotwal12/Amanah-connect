import { Suspense, lazy, useState } from 'react'
import { useWatch } from 'react-hook-form'
import { isApiError, toApiError } from '@/lib/apiError'
import { formatDateTime, todayInIst } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import { Label } from '@/shared/components/ui/label'
import { SimpleSelect } from '@/shared/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { Switch } from '@/shared/components/ui/switch'
import type { AnnouncementView, Uuid } from '@/types'
import {
  AUDIENCES,
  EMPTY_ANNOUNCEMENT,
  announcementSchema,
  announcementToForm,
  instantToIst,
  parseSchedule,
  toCreateAnnouncementRequest,
  toUpdateAnnouncementRequest,
  type AnnouncementInput,
  type AnnouncementValues,
} from '../announcementSchemas'
import {
  useAnnouncementPreview,
  useCreateAnnouncement,
  useScheduleAnnouncement,
  useSendAnnouncement,
  useSendAnnouncementTest,
  useUnscheduleAnnouncement,
  useUpdateAnnouncement,
} from '../hooks/useAnnouncements'
import { useWriteAccess } from '../hooks/usePlan'
import { EmailQuotaMeter } from './EmailQuotaMeter'
import { MemberMultiPicker } from './MemberPicker'
import { SafeHtmlFrame } from './SafeHtmlFrame'

// The editor is the heaviest part of this page: fetched when the composer opens.
const RichTextEditor = lazy(() => import('./RichTextEditor'))

const FIELDS = ['title', 'body', 'audience', 'group', 'memberIds', 'sendEmail']

interface ComposerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** A draft or scheduled announcement to continue; undefined writes a new one. */
  announcement?: AnnouncementView | undefined
}

/**
 * Two steps. Write: the message, who it is for, whether to email it. Check and send: see exactly
 * what members will get and who it reaches, send a test to yourself, then send now or schedule.
 * Moving to the second step saves a draft, so nothing typed is lost.
 */
export function AnnouncementComposer({ open, onOpenChange, announcement }: ComposerProps) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="max-w-2xl">
        {/* Mounted only while open, so every opening starts from a clean state. */}
        <ComposerBody onOpenChange={onOpenChange} announcement={announcement} />
      </SheetContent>
    </Sheet>
  )
}

function ComposerBody({
  onOpenChange,
  announcement,
}: {
  onOpenChange: (open: boolean) => void
  announcement: AnnouncementView | undefined
}) {
  const create = useCreateAnnouncement()
  const update = useUpdateAnnouncement()
  const form = useZodForm(announcementSchema, {
    defaultValues: announcement ? announcementToForm(announcement) : EMPTY_ANNOUNCEMENT,
  })
  const [savedId, setSavedId] = useState<Uuid | null>(announcement?.id ?? null)
  const [savedStatus, setSavedStatus] = useState<AnnouncementView['status']>(
    announcement?.status ?? 'DRAFT',
  )
  const [step, setStep] = useState<'write' | 'check'>('write')
  const [formError, setFormError] = useState<string | null>(null)
  const watched = useWatch({ control: form.control }) as AnnouncementInput

  const saving = create.isPending || update.isPending

  function close(next: boolean) {
    if (!saving) onOpenChange(next)
  }

  /** Create the draft, or update it, and remember which one it is. Returns its id. */
  async function save(values: AnnouncementValues): Promise<Uuid | null> {
    setFormError(null)
    try {
      if (savedId) {
        const done = await update.mutateAsync({
          id: savedId,
          body: toUpdateAnnouncementRequest(values),
        })
        setSavedStatus(done.status)
        return savedId
      }
      const made = await create.mutateAsync(toCreateAnnouncementRequest(values))
      setSavedId(made.id)
      setSavedStatus(made.status)
      return made.id
    } catch (error) {
      if (isApiError(error) && error.code === 'PLAN_LIMIT_EXCEEDED') {
        onOpenChange(false)
        return null
      }
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
      return null
    }
  }

  async function saveDraft(values: AnnouncementValues) {
    const id = await save(values)
    if (id) {
      notify.success('Draft saved')
      onOpenChange(false)
    }
  }

  async function toCheck(values: AnnouncementValues) {
    const id = await save(values)
    if (id) setStep('check')
  }

  return (
    <>
      <SheetHeader>
        <SheetTitle>{step === 'write' ? 'Write an announcement' : 'Check and send'}</SheetTitle>
        <SheetDescription>
          {step === 'write'
            ? 'Members read it in their email. Nothing is sent until you choose to send it.'
            : 'This is what members will get. Try it on yourself first if you like.'}
        </SheetDescription>
      </SheetHeader>

      {step === 'write' ? (
        <Form {...form}>
          <form
            noValidate
            onSubmit={(event) => {
              void form.handleSubmit(toCheck)(event)
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
            <FormField control={form.control} name="title" label="Title" required>
              {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
            </FormField>
            <FormField control={form.control} name="body" label="Message" hideLabel required>
              {({ field, control }) => (
                <div className="grid gap-1.5">
                  <p id="announcement-body-label" className="text-sm font-medium">
                    Message
                    <span aria-hidden="true" className="text-danger">
                      {' '}
                      *
                    </span>
                  </p>
                  <Suspense
                    fallback={
                      <Skeleton className="h-64" role="status" aria-label="Loading the editor" />
                    }
                  >
                    <RichTextEditor
                      value={field.value}
                      onChange={field.onChange}
                      labelledBy="announcement-body-label"
                      invalid={control['aria-invalid']}
                      describedBy={control['aria-describedby']}
                    />
                  </Suspense>
                </div>
              )}
            </FormField>
            <FormField control={form.control} name="audience" label="Who is it for?" required>
              {({ field, control }) => (
                <SimpleSelect
                  id={control.id}
                  options={AUDIENCES}
                  value={field.value}
                  onValueChange={field.onChange}
                />
              )}
            </FormField>
            {watched.audience === 'GROUP' ? (
              <FormField
                control={form.control}
                name="group"
                label="Group"
                required
                description="The group exactly as it is on the members."
              >
                {({ field, control }) => <Input {...field} {...control} autoComplete="off" />}
              </FormField>
            ) : null}
            {watched.audience === 'SELECTED' ? (
              <FormField control={form.control} name="memberIds" label="Members" required>
                {({ field, control }) => (
                  <MemberMultiPicker
                    id={control.id}
                    aria-invalid={control['aria-invalid']}
                    aria-describedby={control['aria-describedby']}
                    value={field.value}
                    onChange={field.onChange}
                  />
                )}
              </FormField>
            ) : null}
            <div className="grid gap-2 rounded border border-brand-100 p-3">
              <div className="flex items-center justify-between gap-3">
                <Label htmlFor="announcement-email">Also send it by email</Label>
                <Switch
                  id="announcement-email"
                  checked={watched.sendEmail}
                  onCheckedChange={(checked) =>
                    form.setValue('sendEmail', checked, { shouldDirty: true })
                  }
                />
              </div>
              <p className="text-sm text-muted">
                {watched.sendEmail
                  ? 'Only members with an email address who agreed to email get it. The next step shows exactly how many.'
                  : 'Email is off, so this is only recorded; nobody is emailed.'}
              </p>
              {watched.sendEmail ? <EmailQuotaMeter /> : null}
            </div>
            <div className="flex flex-wrap justify-end gap-2 border-t border-brand-100 pt-4">
              <Button
                type="button"
                variant="secondary"
                onClick={() => close(false)}
                disabled={saving}
              >
                Cancel
              </Button>
              <Button
                type="button"
                variant="secondary"
                loading={saving}
                onClick={() => {
                  void form.handleSubmit(saveDraft)()
                }}
              >
                Save draft
              </Button>
              <Button type="submit" loading={saving}>
                Check and send
              </Button>
            </div>
          </form>
        </Form>
      ) : savedId ? (
        <CheckStep
          id={savedId}
          status={savedStatus}
          scheduledAt={announcement?.scheduledAt ?? null}
          onBack={() => setStep('write')}
          onDone={() => onOpenChange(false)}
        />
      ) : null}
    </>
  )
}

function CheckStep({
  id,
  status,
  scheduledAt,
  onBack,
  onDone,
}: {
  id: Uuid
  status: AnnouncementView['status']
  scheduledAt: string | null
  onBack: () => void
  onDone: () => void
}) {
  const write = useWriteAccess()
  const preview = useAnnouncementPreview(id)
  const test = useSendAnnouncementTest()
  const send = useSendAnnouncement()
  const schedule = useScheduleAnnouncement()
  const unschedule = useUnscheduleAnnouncement()
  const initial = scheduledAt ? instantToIst(scheduledAt) : null
  const [date, setDate] = useState(initial?.date ?? '')
  const [time, setTime] = useState(initial?.time ?? '09:00')
  const [scheduleError, setScheduleError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const p = preview.data

  async function sendTest() {
    try {
      const done = await test.mutateAsync(id)
      notify.success(`A test was sent to ${done.sentTo}`)
    } catch (error) {
      notify.apiError(error)
    }
  }

  async function doSchedule() {
    const parsed = parseSchedule(date, time)
    if (!parsed.ok) {
      setScheduleError(parsed.message)
      return
    }
    setScheduleError(null)
    try {
      await schedule.mutateAsync({ id, scheduledAt: parsed.at })
      notify.success(`Scheduled for ${formatDateTime(parsed.at)}`)
      onDone()
    } catch (error) {
      setScheduleError(toApiError(error).message)
    }
  }

  return (
    <div className="grid gap-5">
      {preview.isError ? (
        <p
          role="alert"
          className="rounded border border-danger/30 bg-danger-50 p-3 text-sm text-danger"
        >
          {toApiError(preview.error).message}
        </p>
      ) : !p ? (
        <Skeleton className="h-64" role="status" aria-label="Loading the preview" />
      ) : (
        <>
          <div className="grid gap-2">
            <h3 className="font-heading text-lg font-semibold text-brand-900">{p.title}</h3>
            <SafeHtmlFrame title="How the announcement will look" html={p.bodyHtml} />
          </div>
          <dl className="grid gap-2 text-sm" aria-label="Who it reaches">
            <div className="flex justify-between gap-3">
              <dt className="text-muted">In the audience</dt>
              <dd className="font-semibold tabular-nums">
                {p.audienceSize.toLocaleString('en-IN')}
              </dd>
            </div>
            {p.sendEmail ? (
              <>
                <div className="flex justify-between gap-3">
                  <dt className="text-muted">Will be emailed</dt>
                  <dd className="font-semibold tabular-nums">
                    {(p.eligibleForEmail - p.wouldSkipForQuota).toLocaleString('en-IN')}
                  </dd>
                </div>
                {p.noEmail > 0 ? (
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted">No email address</dt>
                    <dd className="tabular-nums">{p.noEmail}</dd>
                  </div>
                ) : null}
                {p.noConsent > 0 ? (
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted">Have not agreed to email</dt>
                    <dd className="tabular-nums">{p.noConsent}</dd>
                  </div>
                ) : null}
                {p.wouldSkipForQuota > 0 ? (
                  <div className="flex justify-between gap-3 text-danger">
                    <dt>Not emailed: allowance runs out</dt>
                    <dd className="tabular-nums">{p.wouldSkipForQuota}</dd>
                  </div>
                ) : null}
              </>
            ) : (
              <p className="text-muted">Email is off. Nobody will be emailed.</p>
            )}
          </dl>
          {p.sendEmail ? <EmailQuotaMeter /> : null}
          {p.wouldSkipForQuota > 0 ? (
            <p role="note" className="rounded border border-gold-500 bg-gold-50 p-3 text-sm">
              Your plan’s monthly email allowance will not cover everyone. The first{' '}
              {p.quotaRemaining?.toLocaleString('en-IN')} are emailed and the rest are skipped. Ask
              about a bigger plan, or wait for next month.
            </p>
          ) : null}
        </>
      )}

      <div className="grid gap-2 rounded border border-brand-100 p-3">
        <p className="text-sm font-medium">Send later</p>
        <div className="flex flex-wrap items-end gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="schedule-date">Date</Label>
            <DatePicker
              id="schedule-date"
              value={date || undefined}
              onValueChange={(value) => setDate(value ?? '')}
              min={todayInIst()}
              className="w-44"
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="schedule-time">Time (India)</Label>
            <Input
              id="schedule-time"
              type="time"
              value={time}
              onChange={(event) => setTime(event.target.value)}
              className="w-32"
            />
          </div>
          <Button
            variant="secondary"
            onClick={() => void doSchedule()}
            loading={schedule.isPending}
            disabled={!write.allowed}
          >
            {status === 'SCHEDULED' ? 'Change the time' : 'Schedule'}
          </Button>
          {status === 'SCHEDULED' ? (
            <Button
              variant="secondary"
              loading={unschedule.isPending}
              onClick={() => {
                void unschedule
                  .mutateAsync(id)
                  .then(() => {
                    notify.success('Back to a draft')
                    onDone()
                  })
                  .catch((error: unknown) => notify.apiError(error))
              }}
            >
              Take off the schedule
            </Button>
          ) : null}
        </div>
        {scheduleError ? (
          <p role="alert" className="text-sm text-danger">
            {scheduleError}
          </p>
        ) : null}
      </div>

      <div className="flex flex-wrap justify-between gap-2 border-t border-brand-100 pt-4">
        <Button variant="secondary" onClick={onBack}>
          Back to editing
        </Button>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            onClick={() => void sendTest()}
            loading={test.isPending}
            disabled={!write.allowed}
          >
            Send a test to me
          </Button>
          <Button onClick={() => setConfirming(true)} disabled={!write.allowed || !p}>
            Send now
          </Button>
        </div>
      </div>

      <ConfirmDialog
        open={confirming}
        onOpenChange={setConfirming}
        title="Send this announcement now?"
        description={
          p
            ? p.sendEmail
              ? `It is emailed to ${(p.eligibleForEmail - p.wouldSkipForQuota).toLocaleString('en-IN')} of ${p.audienceSize.toLocaleString('en-IN')} members and cannot be unsent or sent twice.`
              : 'It is recorded as sent. Nobody is emailed, because email is off.'
            : ''
        }
        confirmLabel="Send now"
        onConfirm={async () => {
          try {
            const done = await send.mutateAsync(id)
            notify.success(
              done.delivery
                ? `Sent: ${done.delivery.emailsQueued.toLocaleString('en-IN')} emailed`
                : 'Sent',
            )
            onDone()
          } catch (error) {
            notify.apiError(error)
            throw error
          }
        }}
      />
    </div>
  )
}
