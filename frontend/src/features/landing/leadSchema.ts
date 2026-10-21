import { z } from 'zod'
import type { PublicLeadRequest } from '@/types'

/** Same rules as the backend's PublicLeadRequest, so most mistakes are caught before the request. */
const PHONE = /^[0-9+()\-. ]{5,30}$/

export const leadSchema = z.object({
  name: z.string().trim().min(1, 'Enter your name').max(150, 'Use 150 characters or fewer'),
  email: z
    .string()
    .trim()
    .min(1, 'Enter your email address')
    .max(254, 'Use 254 characters or fewer')
    .pipe(z.email('Enter a valid email address, such as name@example.com')),
  phone: z
    .string()
    .trim()
    .refine((value) => value === '' || PHONE.test(value), 'Enter a valid phone number'),
  communityName: z.string().trim().max(200, 'Use 200 characters or fewer'),
  size: z
    .string()
    .trim()
    .refine(
      (value) =>
        value === '' ||
        (/^\d{1,8}$/.test(value) && Number(value) >= 1 && Number(value) <= 10_000_000),
      'Enter a whole number between 1 and 10,000,000',
    ),
  message: z.string().trim().max(2000, 'Use 2,000 characters or fewer'),
  /** Honeypot. People never see it, so only a bot fills it. */
  website: z.string(),
})

export type LeadFormValues = z.input<typeof leadSchema>
export type LeadFormOutput = z.output<typeof leadSchema>

export const LEAD_FIELDS = ['name', 'email', 'phone', 'communityName', 'size', 'message'] as const

/** Leave out what was not filled in; the API treats a missing field as "not given". */
export function toLeadRequest(values: LeadFormOutput): PublicLeadRequest {
  return {
    name: values.name,
    email: values.email,
    ...(values.phone ? { phone: values.phone } : {}),
    ...(values.communityName ? { communityName: values.communityName } : {}),
    ...(values.size ? { size: Number(values.size) } : {}),
    ...(values.message ? { message: values.message } : {}),
    website: values.website,
  }
}
