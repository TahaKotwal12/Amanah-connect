import { z } from 'zod'
import { todayInIst } from '@/lib/dates'
import type { CreateMemberRequest, MemberView, UpdateMemberRequest } from '@/types'

/** Same pattern as the backend (MemberDtos.PHONE). */
const PHONE = /^[0-9+()\-. ]{5,30}$/

export const memberSchema = z.object({
  fullName: z
    .string()
    .trim()
    .min(1, 'Enter the member’s name')
    .max(150, 'Use 150 characters or fewer'),
  email: z.union([z.literal(''), z.email('Enter a valid email address').max(254)]),
  phone: z.union([z.literal(''), z.string().trim().regex(PHONE, 'Enter a valid phone number')]),
  group: z.string().trim().max(100, 'Use 100 characters or fewer'),
  joinedOn: z.union([
    z.literal(''),
    z
      .string()
      .regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a date')
      .refine((value) => value <= todayInIst(), { message: 'Choose today or an earlier day' }),
  ]),
  consentEmail: z.boolean(),
  /** Set by the person after the server said another member already has this address. */
  allowDuplicateEmail: z.boolean(),
})

export type MemberInput = z.input<typeof memberSchema>
export type MemberValues = z.output<typeof memberSchema>

export const EMPTY_MEMBER: MemberInput = {
  fullName: '',
  email: '',
  phone: '',
  group: '',
  joinedOn: '',
  consentEmail: true,
  allowDuplicateEmail: false,
}

export function memberToForm(member: MemberView): MemberInput {
  return {
    fullName: member.fullName,
    email: member.email ?? '',
    phone: member.phone ?? '',
    group: member.group ?? '',
    joinedOn: member.joinedOn ?? '',
    consentEmail: member.consentEmail,
    allowDuplicateEmail: false,
  }
}

/** Empty boxes are left out of a new member. */
export function toCreateMemberRequest(values: MemberValues): CreateMemberRequest {
  return {
    fullName: values.fullName,
    consentEmail: values.consentEmail,
    ...(values.email ? { email: values.email } : {}),
    ...(values.phone ? { phone: values.phone } : {}),
    ...(values.group ? { group: values.group } : {}),
    ...(values.joinedOn ? { joinedOn: values.joinedOn } : {}),
    ...(values.allowDuplicateEmail ? { allowDuplicateEmail: true } : {}),
  }
}

/**
 * Only what changed. The server leaves a missing field alone and clears email, phone and group
 * when sent an empty string, so an emptied box goes out as "".
 */
export function toUpdateMemberRequest(
  values: MemberValues,
  original: MemberInput,
): UpdateMemberRequest {
  const body: UpdateMemberRequest = {}
  if (values.fullName !== original.fullName) body.fullName = values.fullName
  if (values.email !== original.email) body.email = values.email
  if (values.phone !== original.phone) body.phone = values.phone
  if (values.group !== original.group) body.group = values.group
  if (values.joinedOn !== original.joinedOn && values.joinedOn) body.joinedOn = values.joinedOn
  if (values.consentEmail !== original.consentEmail) body.consentEmail = values.consentEmail
  if (values.allowDuplicateEmail) body.allowDuplicateEmail = true
  return body
}
