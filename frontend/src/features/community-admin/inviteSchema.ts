import { z } from 'zod'
import type { CreateInviteRequest, InviteByEmailRequest } from '@/types'

const wholeNumber = (min: number, max: number, what: string) =>
  z
    .string()
    .trim()
    .regex(/^\d{1,4}$/, `Enter a whole number from ${min} to ${max}`)
    .refine((value) => Number(value) >= min && Number(value) <= max, {
      message: `${what} must be from ${min} to ${max}`,
    })

/** Mirrors CreateInviteRequest on the backend. */
export const createInviteSchema = z.object({
  expiresInDays: wholeNumber(1, 90, 'Days'),
  maxUses: wholeNumber(1, 1000, 'Uses'),
  defaultGroup: z.string().trim().max(100, 'Use 100 characters or fewer'),
})
export type CreateInviteInput = z.input<typeof createInviteSchema>
export type CreateInviteValues = z.output<typeof createInviteSchema>

export const DEFAULT_INVITE: CreateInviteInput = {
  expiresInDays: '14',
  maxUses: '100',
  defaultGroup: '',
}

export function toCreateInviteRequest(values: CreateInviteValues): CreateInviteRequest {
  return {
    expiresInDays: Number(values.expiresInDays),
    maxUses: Number(values.maxUses),
    ...(values.defaultGroup ? { defaultGroup: values.defaultGroup } : {}),
  }
}

/** Mirrors InviteByEmailRequest. */
export const inviteByEmailSchema = z.object({
  email: z.email('Enter a valid email address').max(254),
  name: z.string().trim().max(150, 'Use 150 characters or fewer'),
  expiresInDays: wholeNumber(1, 90, 'Days'),
  defaultGroup: z.string().trim().max(100, 'Use 100 characters or fewer'),
})
export type InviteByEmailInput = z.input<typeof inviteByEmailSchema>
export type InviteByEmailValues = z.output<typeof inviteByEmailSchema>

export const DEFAULT_EMAIL_INVITE: InviteByEmailInput = {
  email: '',
  name: '',
  expiresInDays: '14',
  defaultGroup: '',
}

export function toInviteByEmailRequest(values: InviteByEmailValues): InviteByEmailRequest {
  return {
    email: values.email,
    expiresInDays: Number(values.expiresInDays),
    ...(values.name ? { name: values.name } : {}),
    ...(values.defaultGroup ? { defaultGroup: values.defaultGroup } : {}),
  }
}
