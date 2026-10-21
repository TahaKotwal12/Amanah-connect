import { useEffect, useRef, useState } from 'react'
import { useWatch } from 'react-hook-form'
import { toApiError } from '@/lib/apiError'
import { todayInIst } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input, Textarea } from '@/shared/components/ui/input'
import { RadioGroup, RadioGroupItem } from '@/shared/components/ui/radio-group'
import { SimpleSelect } from '@/shared/components/ui/select'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/shared/components/ui/sheet'
import { Switch } from '@/shared/components/ui/switch'
import type { PlatformAnnouncementView } from '@/types'
import {
  EMPTY_ANNOUNCEMENT,
  KIND_OPTIONS,
  announcementSchema,
  announcementToForm,
  toCreateRequest,
  toUpdateRequest,
  type AnnouncementInput,
  type AnnouncementValues,
} from '../announcementSchema'
import {
  useCreateAnnouncement,
  useSendAnnouncement,
  useUpdateAnnouncement,
} from '../hooks/useAnnouncements'
import { CommunityMultiPicker } from './CommunityMultiPicker'

interface AnnouncementEditorProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** A draft or scheduled announcement to edit; undefined writes a new one. */
  announcement?: PlatformAnnouncementView | undefined
}

const SERVER_FIELDS: Record<string, keyof AnnouncementInput> = {
  title: 'title',
  body: 'body',
  kind: 'kind',
  communityIds: 'communityIds',
  expiresAt: 'expiresOn',
  scheduledAt: 'scheduleDate',
}

/**
 * Compose, schedule or send a platform announcement. Sending now asks first: it emails every
 * administrator it reaches and cannot be undone or repeated.
 */
export function AnnouncementEditor({ open, onOpenChange, announcement }: AnnouncementEditorProps) {
  const create = useCreateAnnouncement()
  const update = useUpdateAnnouncement()
  const send = useSendAnnouncement()
  const [formError, setFormError] = useState<string | null>(null)
  // If saving worked but sending did not, trying again sends the saved one instead of making a copy.
  const savedId = useRef<string | null>(null)
  const [confirmSend, setConfirmSend] = useState<AnnouncementValues | null>(null)
  const form = useZodForm(announcementSchema, {
    defaultValues: announcement ? announcementToForm(announcement) : EMPTY_ANNOUNCEMENT,
  })

  useEffect(() => {
    if (open) {
      savedId.current = null
      form.reset(announcement ? announcementToForm(announcement) : EMPTY_ANNOUNCEMENT)
    }
  }, [open, announcement, form])

  const watched = useWatch({ control: form.control }) as AnnouncementInput
  const busy = create.isPending || update.isPending || send.isPending

  function close(next: boolean) {
    if (busy) return
    if (!next) setFormError(null)
    onOpenChange(next)
  }

  async function save(values: AnnouncementValues, sendNow: boolean) {
    setFormError(null)
    try {
      let id = savedId.current ?? announcement?.id ?? null
      if (savedId.current === null) {
        const saved = announcement
          ? await update.mutateAsync({ id: announcement.id, body: toUpdateRequest(values) })
          : await create.mutateAsync(toCreateRequest(values))
        id = saved.id
        savedId.current = saved.id
      }
      if (sendNow && id) {
        await send.mutateAsync(id)
        notify.success('Announcement sent')
      } else {
        notify.success(values.when === 'later' ? 'Announcement scheduled' : 'Draft saved')
      }
      onOpenChange(false)
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, Object.keys(SERVER_FIELDS))
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
      throw error
    }
  }

  function submit(values: AnnouncementValues) {
    if (values.when === 'now') {
      setConfirmSend(values)
      return
    }
    void save(values, false).catch(() => undefined)
  }

  const primaryLabel =
    watched.when === 'now' ? 'Send now' : watched.when === 'later' ? 'Schedule' : 'Save draft'

  return (
    <Sheet open={open} onOpenChange={close}>
      <SheetContent className="max-w-xl">
        <SheetHeader>
          <SheetTitle>{announcement ? 'Edit announcement' : 'New announcement'}</SheetTitle>
          <SheetDescription>
            A message to community administrators: by email, as a banner in the app, or both.
          </SheetDescription>
        </SheetHeader>

        <Form {...form}>
          <form
            noValidate
            onSubmit={(event) => {
              void form.handleSubmit(submit)(event)
            }}
            className="grid gap-5"
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
            <FormField
              control={form.control}
              name="body"
              label="Message"
              required
              description="Plain text. Blank lines make paragraphs."
            >
              {({ field, control }) => <Textarea {...field} {...control} rows={8} />}
            </FormField>
            <FormField control={form.control} name="kind" label="Kind">
              {({ field, control }) => (
                <SimpleSelect
                  id={control.id}
                  options={KIND_OPTIONS}
                  value={field.value}
                  onValueChange={field.onChange}
                />
              )}
            </FormField>

            <fieldset className="grid gap-3 rounded border border-brand-100 p-4">
              <legend className="px-1 text-sm font-semibold text-brand-900">Who gets it</legend>
              <FormField control={form.control} name="audience" label="Audience" hideLabel asGroup>
                {({ field }) => (
                  <RadioGroup
                    value={field.value}
                    onValueChange={field.onChange}
                    aria-label="Audience"
                  >
                    <RadioGroupItem value="all" label="Every active community" />
                    <RadioGroupItem value="selected" label="Only the communities I choose" />
                  </RadioGroup>
                )}
              </FormField>
              {watched.audience === 'selected' ? (
                <FormField control={form.control} name="communityIds" label="Communities" required>
                  {({ field, control }) => (
                    <CommunityMultiPicker
                      id={control.id}
                      aria-invalid={control['aria-invalid']}
                      aria-describedby={control['aria-describedby']}
                      value={field.value}
                      onChange={field.onChange}
                    />
                  )}
                </FormField>
              ) : null}
            </fieldset>

            <fieldset className="grid gap-3 rounded border border-brand-100 p-4">
              <legend className="px-1 text-sm font-semibold text-brand-900">
                How it reaches them
              </legend>
              <Switch
                label="Email each administrator"
                checked={watched.sendEmail === true}
                onCheckedChange={(checked) =>
                  form.setValue('sendEmail', checked, { shouldDirty: true, shouldValidate: true })
                }
              />
              <Switch
                label="Show a banner in the app"
                checked={watched.banner === true}
                onCheckedChange={(checked) =>
                  form.setValue('banner', checked, { shouldDirty: true, shouldValidate: true })
                }
              />
              {form.formState.errors.sendEmail?.message ? (
                <p role="alert" className="text-sm text-danger">
                  {form.formState.errors.sendEmail.message}
                </p>
              ) : null}
              <FormField
                control={form.control}
                name="expiresOn"
                label="Stop showing it after"
                description="Optional. The banner is removed at the end of this day."
              >
                {({ field, control }) => (
                  <DatePicker
                    id={control.id}
                    aria-invalid={control['aria-invalid']}
                    aria-describedby={control['aria-describedby']}
                    value={field.value || undefined}
                    onValueChange={(value) => field.onChange(value ?? '')}
                    onBlur={field.onBlur}
                    min={todayInIst()}
                  />
                )}
              </FormField>
            </fieldset>

            <fieldset className="grid gap-3 rounded border border-brand-100 p-4">
              <legend className="px-1 text-sm font-semibold text-brand-900">When</legend>
              <FormField control={form.control} name="when" label="When" hideLabel asGroup>
                {({ field }) => (
                  <RadioGroup value={field.value} onValueChange={field.onChange} aria-label="When">
                    <RadioGroupItem value="draft" label="Save as a draft for now" />
                    <RadioGroupItem value="later" label="Send at a time I choose" />
                    <RadioGroupItem value="now" label="Send it now" />
                  </RadioGroup>
                )}
              </FormField>
              {watched.when === 'later' ? (
                <div className="grid gap-4 sm:grid-cols-2">
                  <FormField
                    control={form.control}
                    name="scheduleDate"
                    label="Day (India time)"
                    required
                  >
                    {({ field, control }) => (
                      <DatePicker
                        id={control.id}
                        aria-invalid={control['aria-invalid']}
                        aria-describedby={control['aria-describedby']}
                        value={field.value || undefined}
                        onValueChange={(value) => field.onChange(value ?? '')}
                        onBlur={field.onBlur}
                        min={todayInIst()}
                      />
                    )}
                  </FormField>
                  <FormField control={form.control} name="scheduleTime" label="Time" required>
                    {({ field, control }) => <Input {...field} {...control} type="time" />}
                  </FormField>
                </div>
              ) : null}
            </fieldset>

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
                {primaryLabel}
              </Button>
            </div>
          </form>
        </Form>

        <ConfirmDialog
          open={confirmSend !== null}
          onOpenChange={(next) => !next && setConfirmSend(null)}
          variant="danger"
          title="Send this announcement now?"
          description={
            watched.audience === 'selected'
              ? `It goes to the administrators of the ${watched.communityIds.length} communities you chose. This cannot be undone.`
              : 'It goes to the administrators of every active community. This cannot be undone.'
          }
          confirmLabel="Send now"
          onConfirm={async () => {
            if (!confirmSend) return
            await save(confirmSend, true)
          }}
        />
      </SheetContent>
    </Sheet>
  )
}
