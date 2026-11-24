import { useEffect, useState } from 'react'
import { toApiError } from '@/lib/apiError'
import { notify } from '@/lib/notify'
import { uploadToPresignedUrl } from '@/lib/upload'
import { Button } from '@/shared/components/ui/button'
import { ErrorState } from '@/shared/components/ui/error-state'
import { FileUpload } from '@/shared/components/ui/file-upload'
import { applyApiFieldErrors, useZodForm } from '@/shared/components/ui/form'
import { Form, FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'
import { SimpleSelect } from '@/shared/components/ui/select'
import { Skeleton } from '@/shared/components/ui/skeleton'
import type { SettingsView } from '@/types'
import { requestLogoUpload } from '../../api/settings'
import { useRemoveLogo, useUpdateSettings } from '../../hooks/useSettingsActions'
import { useWriteAccess } from '../../hooks/usePlan'
import { useSettings } from '../../hooks/useSettings'
import {
  MONTHS,
  isUnchanged,
  profileDefaults,
  profileSchema,
  toUpdateRequest,
  type ProfileValues,
} from '../../settingsSchemas'

const LOGO_TYPES = ['image/png', 'image/jpeg', 'image/webp'] as const
const LOGO_MAX_BYTES = 512 * 1024
const FIELDS = [
  'name',
  'contactName',
  'contactEmail',
  'contactPhone',
  'addressLine1',
  'addressLine2',
  'city',
  'state',
  'postalCode',
  'country',
  'memberGroupLabel',
  'currency',
  'financialYearStartMonth',
]

/** The community's name, logo, contact, address and the few settings that shape how money is shown. */
export function ProfileTab() {
  const settings = useSettings()
  if (settings.isLoading) {
    return <Skeleton className="h-96" role="status" aria-label="Loading the profile" />
  }
  if (settings.isError || !settings.data) {
    return <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
  }
  return (
    <div className="grid gap-8">
      <LogoSection settings={settings.data} />
      <ProfileForm settings={settings.data} />
    </div>
  )
}

function LogoSection({ settings }: { settings: SettingsView }) {
  const update = useUpdateSettings()
  const remove = useRemoveLogo()
  const write = useWriteAccess()

  return (
    <section aria-labelledby="logo-heading" className="grid gap-3">
      <h2 id="logo-heading" className="font-heading text-lg font-semibold text-brand-900">
        Logo
      </h2>
      <div className="flex flex-wrap items-start gap-4">
        {settings.logoUrl ? (
          <img
            src={settings.logoUrl}
            alt={`${settings.name} logo`}
            width={96}
            height={96}
            className="size-24 rounded border border-brand-100 bg-white object-contain"
          />
        ) : (
          <p className="flex size-24 items-center justify-center rounded border border-dashed border-brand-200 px-2 text-center text-sm text-muted">
            No logo yet
          </p>
        )}
        <div className="min-w-0 flex-1">
          <FileUpload<string>
            label="Upload a logo"
            hint="PNG, JPEG or WebP, up to 512 KB. It appears in your emails and receipts."
            accept={LOGO_TYPES}
            maxBytes={LOGO_MAX_BYTES}
            disabled={!write.allowed}
            upload={async (file, context) => {
              const instructions = await requestLogoUpload(file)
              await uploadToPresignedUrl(instructions, file, context)
              return instructions.logoKey
            }}
            onUploaded={(_file, logoKey) => {
              void update
                .mutateAsync({ logoKey, version: settings.version })
                .then(() => notify.success('The logo was changed'))
                .catch((error: unknown) => notify.apiError(error))
            }}
          />
          {settings.logoKey ? (
            <Button
              variant="ghost"
              className="mt-2"
              loading={remove.isPending}
              disabled={!write.allowed}
              onClick={() =>
                void remove
                  .mutateAsync()
                  .then(() => notify.success('The logo was removed'))
                  .catch((error: unknown) => notify.apiError(error))
              }
            >
              Remove the logo
            </Button>
          ) : null}
        </div>
      </div>
    </section>
  )
}

function ProfileForm({ settings }: { settings: SettingsView }) {
  const update = useUpdateSettings()
  const write = useWriteAccess()
  const form = useZodForm(profileSchema, { defaultValues: profileDefaults(settings) })
  const [formError, setFormError] = useState<string | null>(null)

  // A change made elsewhere (the logo, another admin) arrives as a new version: start from it.
  useEffect(() => {
    form.reset(profileDefaults(settings))
  }, [settings, form])

  async function submit(values: ProfileValues) {
    setFormError(null)
    const body = toUpdateRequest(values, settings)
    if (isUnchanged(body)) {
      notify.info('Nothing to save: no field was changed')
      return
    }
    try {
      await update.mutateAsync(body)
      notify.success('The profile was saved')
    } catch (error) {
      const unmatched = applyApiFieldErrors(form, error, FIELDS)
      const rest = Object.values(unmatched)
      setFormError(rest.length > 0 ? rest.join(' ') : toApiError(error).message)
    }
  }

  const disabled = !write.allowed
  const locked = settings.financialSettingsLocked

  return (
    <Form {...form}>
      <form
        noValidate
        className="grid max-w-3xl gap-5"
        onSubmit={(event) => {
          void form.handleSubmit(submit)(event)
        }}
      >
        <h2 className="font-heading text-lg font-semibold text-brand-900">Community</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField control={form.control} name="name" label="Name" required>
            {({ field, control }) => <Input {...field} {...control} disabled={disabled} />}
          </FormField>
          <FormField
            control={form.control}
            name="memberGroupLabel"
            label="What you call a group"
            description="For example Flat, Family or Batch. It labels the group on each member."
            required
          >
            {({ field, control }) => <Input {...field} {...control} disabled={disabled} />}
          </FormField>
        </div>

        <h2 className="font-heading text-lg font-semibold text-brand-900">Contact</h2>
        <div className="grid gap-4 sm:grid-cols-3">
          <FormField control={form.control} name="contactName" label="Contact person">
            {({ field, control }) => <Input {...field} {...control} disabled={disabled} />}
          </FormField>
          <FormField control={form.control} name="contactEmail" label="Contact email">
            {({ field, control }) => (
              <Input {...field} {...control} type="email" autoComplete="off" disabled={disabled} />
            )}
          </FormField>
          <FormField control={form.control} name="contactPhone" label="Contact phone">
            {({ field, control }) => (
              <Input {...field} {...control} type="tel" autoComplete="off" disabled={disabled} />
            )}
          </FormField>
        </div>

        <h2 className="font-heading text-lg font-semibold text-brand-900">Address</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField control={form.control} name="addressLine1" label="Address line 1">
            {({ field, control }) => <Input {...field} {...control} disabled={disabled} />}
          </FormField>
          <FormField control={form.control} name="addressLine2" label="Address line 2">
            {({ field, control }) => <Input {...field} {...control} disabled={disabled} />}
          </FormField>
          <FormField control={form.control} name="city" label="City">
            {({ field, control }) => <Input {...field} {...control} disabled={disabled} />}
          </FormField>
          <FormField control={form.control} name="state" label="State">
            {({ field, control }) => <Input {...field} {...control} disabled={disabled} />}
          </FormField>
          <FormField control={form.control} name="postalCode" label="Postal code">
            {({ field, control }) => <Input {...field} {...control} disabled={disabled} />}
          </FormField>
          <FormField
            control={form.control}
            name="country"
            label="Country"
            description="A 2-letter code, for example IN."
          >
            {({ field, control }) => (
              <Input
                {...field}
                {...control}
                maxLength={2}
                className="uppercase"
                disabled={disabled}
              />
            )}
          </FormField>
        </div>

        <h2 className="font-heading text-lg font-semibold text-brand-900">Money</h2>
        {locked ? (
          <p role="note" className="rounded bg-brand-50 px-3 py-2 text-sm text-brand-900">
            The currency and the financial year cannot change any more, because invoices, payments
            or ledger entries already exist.
          </p>
        ) : null}
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField
            control={form.control}
            name="currency"
            label="Currency"
            description="A 3-letter code, for example INR."
            required
          >
            {({ field, control }) => (
              <Input
                {...field}
                {...control}
                maxLength={3}
                className="uppercase"
                disabled={disabled || locked}
              />
            )}
          </FormField>
          <FormField
            control={form.control}
            name="financialYearStartMonth"
            label="Financial year starts in"
            required
          >
            {({ field, control }) => (
              <SimpleSelect
                id={control.id}
                options={MONTHS}
                value={field.value}
                onValueChange={field.onChange}
                disabled={disabled || locked}
              />
            )}
          </FormField>
        </div>

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
            Save the profile
          </Button>
        </div>
      </form>
    </Form>
  )
}
