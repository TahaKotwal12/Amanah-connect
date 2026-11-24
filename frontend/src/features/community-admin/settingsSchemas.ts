import { z } from 'zod'
import type { SettingsView, UpdateSettingsRequest } from '@/types'

/** Months for the financial year start. April is the Indian default. */
export const MONTHS: ReadonlyArray<{ value: string; label: string }> = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
].map((label, index) => ({ value: String(index + 1), label }))

const PHONE = /^$|^\+?[0-9 ()-]{7,20}$/

const optionalText = (max: number, message = `Use at most ${max} characters`) =>
  z.string().trim().max(max, message)

export const profileSchema = z.object({
  name: z.string().trim().min(1, 'Enter the community name').max(200, 'Use at most 200 characters'),
  contactName: optionalText(150),
  contactEmail: z
    .string()
    .trim()
    .max(254, 'Use at most 254 characters')
    .refine(
      (value) => value === '' || z.email().safeParse(value).success,
      'Enter a valid email address',
    ),
  contactPhone: z.string().trim().regex(PHONE, 'Enter a valid phone number'),
  addressLine1: optionalText(200),
  addressLine2: optionalText(200),
  city: optionalText(100),
  state: optionalText(100),
  postalCode: optionalText(20),
  country: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^([A-Z]{2})?$/, 'Use a 2-letter country code, for example IN'),
  memberGroupLabel: z
    .string()
    .trim()
    .min(1, 'Enter what you call a group, for example Flat or Family')
    .max(30, 'Use at most 30 characters'),
  currency: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^[A-Z]{3}$/, 'Use a 3-letter currency code, for example INR'),
  financialYearStartMonth: z.string().regex(/^(1[0-2]|[1-9])$/, 'Choose a month'),
})
export type ProfileInput = z.input<typeof profileSchema>
export type ProfileValues = z.output<typeof profileSchema>

export function profileDefaults(s: SettingsView): ProfileInput {
  return {
    name: s.name,
    contactName: s.contactName ?? '',
    contactEmail: s.contactEmail ?? '',
    contactPhone: s.contactPhone ?? '',
    addressLine1: s.addressLine1 ?? '',
    addressLine2: s.addressLine2 ?? '',
    city: s.city ?? '',
    state: s.state ?? '',
    postalCode: s.postalCode ?? '',
    country: s.country ?? '',
    memberGroupLabel: s.memberGroupLabel,
    currency: s.currency,
    financialYearStartMonth: String(s.financialYearStartMonth),
  }
}

/**
 * Only what changed goes to the server (PATCH is partial), plus the version that was read so a
 * change made by someone else in the meantime is refused instead of overwritten. Currency and the
 * financial year are left out once the server has locked them.
 */
export function toUpdateRequest(
  values: ProfileValues,
  current: SettingsView,
): UpdateSettingsRequest {
  const before = profileDefaults(current)
  const body: UpdateSettingsRequest = { version: current.version }
  const text = [
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
  ] as const
  for (const key of text) {
    if (values[key] !== before[key]) body[key] = values[key]
  }
  if (!current.financialSettingsLocked) {
    if (values.currency !== before.currency) body.currency = values.currency
    if (values.financialYearStartMonth !== before.financialYearStartMonth) {
      body.financialYearStartMonth = Number(values.financialYearStartMonth)
    }
  }
  return body
}

/** True when `toUpdateRequest` would send nothing but the version. */
export const isUnchanged = (body: UpdateSettingsRequest): boolean => Object.keys(body).length <= 1

// ---- notifications ----------------------------------------------------------------------------

export const notificationsSchema = z.object({
  dueReminderDaysBefore: z
    .string()
    .regex(/^\d+$/, 'Enter a whole number of days')
    .refine((v) => Number(v) <= 60, 'At most 60 days before'),
  overdueReminderEveryDays: z
    .string()
    .regex(/^\d+$/, 'Enter a whole number of days')
    .refine((v) => Number(v) >= 1 && Number(v) <= 90, 'Between 1 and 90 days'),
  sendWelcomeEmail: z.boolean(),
  sendReceiptEmail: z.boolean(),
})
export type NotificationsInput = z.input<typeof notificationsSchema>
export type NotificationsValues = z.output<typeof notificationsSchema>

export function notificationsDefaults(s: SettingsView): NotificationsInput {
  return {
    dueReminderDaysBefore: String(s.notifications.dueReminderDaysBefore),
    overdueReminderEveryDays: String(s.notifications.overdueReminderEveryDays),
    sendWelcomeEmail: s.notifications.sendWelcomeEmail,
    sendReceiptEmail: s.notifications.sendReceiptEmail,
  }
}

export function toNotificationsRequest(
  values: NotificationsValues,
  current: SettingsView,
): UpdateSettingsRequest {
  return {
    version: current.version,
    notifications: {
      dueReminderDaysBefore: Number(values.dueReminderDaysBefore),
      overdueReminderEveryDays: Number(values.overdueReminderEveryDays),
      sendWelcomeEmail: values.sendWelcomeEmail,
      sendReceiptEmail: values.sendReceiptEmail,
    },
  }
}
