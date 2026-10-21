import type { Control } from 'react-hook-form'
import { todayInIst } from '@/lib/dates'
import { DatePicker } from '@/shared/components/ui/date-picker'
import { FormField } from '@/shared/components/ui/form-field'
import { Input } from '@/shared/components/ui/input'

/** The fields the create wizard and the edit form have in common. */
export interface ProfileInput {
  name: string
  contactName: string
  contactEmail: string
  contactPhone: string
  addressLine1: string
  addressLine2: string
  city: string
  state: string
  postalCode: string
  country: string
  dateOfEstablishment: string
}

/**
 * Name, contact, address and founding date. `control` comes from either form: both are supersets
 * of ProfileInput, so callers cast it once (`control as unknown as Control<ProfileInput>`).
 */
export function ProfileFields({ control }: { control: Control<ProfileInput> }) {
  return (
    <div className="grid items-start gap-4 sm:grid-cols-2">
      <FormField
        control={control}
        name="name"
        label="Community name"
        required
        className="sm:col-span-2"
      >
        {({ field, control: c }) => <Input {...field} {...c} autoComplete="off" />}
      </FormField>
      <FormField control={control} name="contactName" label="Contact person">
        {({ field, control: c }) => <Input {...field} {...c} autoComplete="off" />}
      </FormField>
      <FormField control={control} name="contactPhone" label="Contact phone">
        {({ field, control: c }) => <Input {...field} {...c} type="tel" autoComplete="off" />}
      </FormField>
      <FormField
        control={control}
        name="contactEmail"
        label="Contact email"
        className="sm:col-span-2"
      >
        {({ field, control: c }) => <Input {...field} {...c} type="email" autoComplete="off" />}
      </FormField>
      <FormField control={control} name="addressLine1" label="Address line 1">
        {({ field, control: c }) => <Input {...field} {...c} autoComplete="off" />}
      </FormField>
      <FormField control={control} name="addressLine2" label="Address line 2">
        {({ field, control: c }) => <Input {...field} {...c} autoComplete="off" />}
      </FormField>
      <FormField control={control} name="city" label="City">
        {({ field, control: c }) => <Input {...field} {...c} autoComplete="off" />}
      </FormField>
      <FormField control={control} name="state" label="State">
        {({ field, control: c }) => <Input {...field} {...c} autoComplete="off" />}
      </FormField>
      <FormField control={control} name="postalCode" label="Postal code">
        {({ field, control: c }) => <Input {...field} {...c} autoComplete="off" />}
      </FormField>
      <FormField
        control={control}
        name="country"
        label="Country code"
        description="Two letters, for example IN."
      >
        {({ field, control: c }) => <Input {...field} {...c} maxLength={2} autoComplete="off" />}
      </FormField>
      <FormField control={control} name="dateOfEstablishment" label="Founded on">
        {({ field, control: c }) => (
          <DatePicker
            id={c.id}
            aria-invalid={c['aria-invalid']}
            aria-describedby={c['aria-describedby']}
            value={field.value || undefined}
            onValueChange={(value) => field.onChange(value ?? '')}
            onBlur={field.onBlur}
            max={todayInIst()}
          />
        )}
      </FormField>
    </div>
  )
}
