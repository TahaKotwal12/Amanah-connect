import { useEffect, useState } from 'react'
import { Controller } from 'react-hook-form'
import { toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { Checkbox } from '@/shared/components/ui/checkbox'
import { ErrorState } from '@/shared/components/ui/error-state'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import { Skeleton } from '@/shared/components/ui/skeleton'
import type { SettingsView } from '@/types'
import { useUpdateSettings } from '../../hooks/useSettingsActions'
import { useWriteAccess } from '../../hooks/usePlan'
import { useSettings } from '../../hooks/useSettings'
import {
  notificationsDefaults,
  notificationsSchema,
  toNotificationsRequest,
  type NotificationsValues,
} from '../../settingsSchemas'

const FIELDS = ['dueReminderDaysBefore', 'overdueReminderEveryDays']

/** When members are reminded, and which emails the system sends for you. */
export function NotificationsTab() {
  const settings = useSettings()
  if (settings.isLoading) {
    return (
      <Skeleton className="h-64" role="status" aria-label="Loading the notification settings" />
    )
  }
  if (settings.isError || !settings.data) {
    return <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
  }
  return <NotificationsForm settings={settings.data} />
}

function NotificationsForm({ settings }: { settings: SettingsView }) {
  const update = useUpdateSettings()
  const write = useWriteAccess()
  const form = useZodForm(notificationsSchema, { defaultValues: notificationsDefaults(settings) })
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    form.reset(notificationsDefaults(settings))
  }, [settings, form])

  async function submit(values: NotificationsValues) {
    setFormError(null)
    try {
      await update.mutateAsync(toNotificationsRequest(values, settings))
      notify.success('The notification settings were saved')
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  const disabled = !write.allowed
  return (
    <Form {...form}>
      <form
        noValidate
        className="grid max-w-2xl gap-5"
        onSubmit={(event) => {
          void form.handleSubmit(submit)(event)
        }}
      >
        <h2 className="font-heading text-lg font-semibold text-brand-900">Reminders</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField
            control={form.control}
            name="dueReminderDaysBefore"
            label="Remind before the due date"
            description="Days before a bill is due. 0 turns it off."
            required
          >
            {({ field, control }) => (
              <Input {...field} {...control} inputMode="numeric" disabled={disabled} />
            )}
          </FormField>
          <FormField
            control={form.control}
            name="overdueReminderEveryDays"
            label="Repeat when overdue, every"
            description="Days between reminders once a bill is late (1 to 90)."
            required
          >
            {({ field, control }) => (
              <Input {...field} {...control} inputMode="numeric" disabled={disabled} />
            )}
          </FormField>
        </div>

        <h2 className="font-heading text-lg font-semibold text-brand-900">
          Emails the system sends
        </h2>
        <Controller
          control={form.control}
          name="sendWelcomeEmail"
          render={({ field }) => (
            <Checkbox
              label="Welcome email"
              description="Sent when a member joins, with their member number."
              checked={field.value}
              onCheckedChange={(checked) => field.onChange(checked === true)}
              disabled={disabled}
            />
          )}
        />
        <Controller
          control={form.control}
          name="sendReceiptEmail"
          render={({ field }) => (
            <Checkbox
              label="Receipt email"
              description="Sent with the receipt when you record a payment."
              checked={field.value}
              onCheckedChange={(checked) => field.onChange(checked === true)}
              disabled={disabled}
            />
          )}
        />
        <p className="text-sm text-muted">
          Bills are always emailed when you divide an expense, unless you untick that on the day.
        </p>

        {formError ? (
          <p role="alert" className="text-sm text-danger">
            {formError}
          </p>
        ) : null}
        <div>
          <Button
            type="submit"
            loading={update.isPending}
            disabled={disabled}
            {...(write.reason ? { title: write.reason } : {})}
          >
            Save
          </Button>
        </div>
      </form>
    </Form>
  )
}
