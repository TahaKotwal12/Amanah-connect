import { z } from 'zod'
import { todayInIst } from '@/lib/dates'
import type { CreateCommunityRequest, IsoDate, UpdateCommunityRequest, Uuid } from '@/types'

/** Same patterns as the backend (AdminCommunityDtos.java). */
const PHONE = /^[0-9+()\-\s.]{3,30}$/
const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/
const COUNTRY = /^[A-Z]{2}$/

const text = (max: number) => z.string().trim().max(max, `Use ${max} characters or fewer`)
/** Optional: an empty box is fine, anything typed must be valid. */
const optionalEmail = z.union([z.literal(''), z.email('Enter a valid email address').max(254)])
const optionalPhone = z.union([
  z.literal(''),
  z.string().trim().regex(PHONE, 'Enter a valid phone number'),
])
const optionalDate = z.union([
  z.literal(''),
  z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a date')
    .refine((value) => value < todayInIst(), { message: 'Choose a date in the past' }),
])

/** The profile fields shared by "create" and "edit". */
const profileShape = {
  name: z
    .string()
    .trim()
    .min(1, 'Enter the community name')
    .max(200, 'Use 200 characters or fewer'),
  contactName: text(150),
  contactEmail: optionalEmail,
  contactPhone: optionalPhone,
  addressLine1: text(200),
  addressLine2: text(200),
  city: text(100),
  state: text(100),
  postalCode: text(20),
  country: z.union([
    z.literal(''),
    z.string().trim().toUpperCase().regex(COUNTRY, 'Use a 2-letter country code, such as IN'),
  ]),
  dateOfEstablishment: optionalDate,
}

export const createCommunitySchema = z.object({
  ...profileShape,
  slug: z.union([
    z.literal(''),
    z
      .string()
      .trim()
      .max(80, 'Use 80 characters or fewer')
      .regex(SLUG, 'Use lower case letters, digits and hyphens'),
  ]),
  financialYearStartMonth: z.string().regex(/^(?:[1-9]|1[0-2])$/, 'Choose a month'),
  planId: z.string().min(1, 'Choose a plan'),
  ownerName: z
    .string()
    .trim()
    .min(1, 'Enter the owner’s name')
    .max(150, 'Use 150 characters or fewer'),
  ownerEmail: z.email('Enter a valid email address').max(254),
})
export type CreateCommunityInput = z.input<typeof createCommunitySchema>
export type CreateCommunityValues = z.output<typeof createCommunitySchema>

/** Which fields each wizard step owns, so "Next" can validate just its own. */
export const CREATE_STEP_FIELDS = [
  [
    'name',
    'slug',
    'contactName',
    'contactEmail',
    'contactPhone',
    'addressLine1',
    'addressLine2',
    'city',
    'state',
    'postalCode',
    'country',
    'dateOfEstablishment',
    'financialYearStartMonth',
  ],
  ['planId'],
  ['ownerName', 'ownerEmail'],
] as const satisfies ReadonlyArray<ReadonlyArray<keyof CreateCommunityInput>>

export const EMPTY_CREATE: CreateCommunityInput = {
  name: '',
  slug: '',
  contactName: '',
  contactEmail: '',
  contactPhone: '',
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  postalCode: '',
  country: 'IN',
  dateOfEstablishment: '',
  financialYearStartMonth: '4', // April: the Indian financial year
  planId: '',
  ownerName: '',
  ownerEmail: '',
}

/** Empty boxes are left out of the request, not sent as empty strings. */
function present<T extends Record<string, string>>(fields: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(fields).filter(([, value]) => value !== ''),
  ) as Partial<T>
}

export function toCreateRequest(
  values: CreateCommunityValues,
  leadId?: Uuid | null,
): CreateCommunityRequest {
  const optional = present({
    slug: values.slug,
    contactName: values.contactName,
    contactEmail: values.contactEmail,
    contactPhone: values.contactPhone,
    addressLine1: values.addressLine1,
    addressLine2: values.addressLine2,
    city: values.city,
    state: values.state,
    postalCode: values.postalCode,
    country: values.country,
    dateOfEstablishment: values.dateOfEstablishment,
  })
  return {
    name: values.name,
    planId: values.planId,
    ownerName: values.ownerName,
    ownerEmail: values.ownerEmail,
    financialYearStartMonth: Number(values.financialYearStartMonth),
    ...optional,
    ...(leadId ? { leadId } : {}),
  }
}

/** A lead's pre-filled request, as wizard form values. */
export function draftToForm(request: CreateCommunityRequest): CreateCommunityInput {
  return {
    ...EMPTY_CREATE,
    name: request.name,
    slug: request.slug ?? '',
    contactName: request.contactName ?? '',
    contactEmail: request.contactEmail ?? '',
    contactPhone: request.contactPhone ?? '',
    addressLine1: request.addressLine1 ?? '',
    addressLine2: request.addressLine2 ?? '',
    city: request.city ?? '',
    state: request.state ?? '',
    postalCode: request.postalCode ?? '',
    country: request.country ?? 'IN',
    dateOfEstablishment: request.dateOfEstablishment ?? '',
    financialYearStartMonth: String(request.financialYearStartMonth ?? 4),
    planId: request.planId,
    ownerName: request.ownerName,
    ownerEmail: request.ownerEmail,
  }
}

// ---- editing ----------------------------------------------------------------------------------

export const editCommunitySchema = z.object({
  ...profileShape,
  require2fa: z.boolean(),
})
export type EditCommunityInput = z.input<typeof editCommunitySchema>
export type EditCommunityValues = z.output<typeof editCommunitySchema>

export function toEditForm(community: {
  name: string
  contactName: string | null
  contactEmail: string | null
  contactPhone: string | null
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  dateOfEstablishment: IsoDate | null
  require2fa: boolean
}): EditCommunityInput {
  return {
    name: community.name,
    contactName: community.contactName ?? '',
    contactEmail: community.contactEmail ?? '',
    contactPhone: community.contactPhone ?? '',
    addressLine1: community.addressLine1 ?? '',
    addressLine2: community.addressLine2 ?? '',
    city: community.city ?? '',
    state: community.state ?? '',
    postalCode: community.postalCode ?? '',
    country: community.country ?? '',
    dateOfEstablishment: community.dateOfEstablishment ?? '',
    require2fa: community.require2fa,
  }
}

/**
 * The update body. The API treats a missing field as "leave alone", so a box the person emptied
 * cannot be cleared this way: it is simply not sent. Only changed fields go out.
 */
export function toUpdateRequest(
  values: EditCommunityValues,
  original: EditCommunityInput,
  version: number,
): UpdateCommunityRequest {
  const changed: Record<string, string | boolean> = {}
  for (const [key, value] of Object.entries(values) as Array<
    [keyof EditCommunityValues, string | boolean]
  >) {
    if (value !== original[key] && value !== '') changed[key] = value
  }
  return { ...changed, version }
}
