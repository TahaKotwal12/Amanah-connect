import { z } from 'zod'
import { istToInstant, instantToIst } from '@/lib/ist'
import type {
  AnnouncementAudience,
  AnnouncementView,
  CreateAnnouncementRequest,
  UpdateAnnouncementRequest,
} from '@/types'

export const AUDIENCES: ReadonlyArray<{
  value: Exclude<AnnouncementAudience, 'COMMUNITY_ADMINS'>
  label: string
}> = [
  { value: 'ALL_ACTIVE', label: 'Every active member' },
  { value: 'GROUP', label: 'One group' },
  { value: 'SELECTED', label: 'Members I choose' },
]

/** The words in some HTML, to tell an empty editor ("<p></p>") from a real message. */
export function plainText(html: string): string {
  return html
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export const announcementSchema = z
  .object({
    title: z.string().trim().min(1, 'Enter a title').max(200, 'Use 200 characters or fewer'),
    body: z
      .string()
      .max(20000, 'This message is too long')
      .refine((html) => plainText(html) !== '', { message: 'Write the message' }),
    audience: z.enum(['ALL_ACTIVE', 'GROUP', 'SELECTED']),
    group: z.string().trim().max(100, 'Use 100 characters or fewer'),
    memberIds: z.array(z.string()),
    sendEmail: z.boolean(),
  })
  .superRefine((value, context) => {
    if (value.audience === 'GROUP' && value.group === '') {
      context.addIssue({ code: 'custom', path: ['group'], message: 'Enter the group to write to' })
    }
    if (value.audience === 'SELECTED' && value.memberIds.length === 0) {
      context.addIssue({
        code: 'custom',
        path: ['memberIds'],
        message: 'Choose at least one member',
      })
    }
  })
export type AnnouncementInput = z.input<typeof announcementSchema>
export type AnnouncementValues = z.output<typeof announcementSchema>

export const EMPTY_ANNOUNCEMENT: AnnouncementInput = {
  title: '',
  body: '',
  audience: 'ALL_ACTIVE',
  group: '',
  memberIds: [],
  sendEmail: true,
}

export function announcementToForm(view: AnnouncementView): AnnouncementInput {
  return {
    title: view.title,
    body: view.bodyHtml,
    audience: view.audience === 'COMMUNITY_ADMINS' ? 'ALL_ACTIVE' : view.audience,
    group: view.group ?? '',
    memberIds: view.memberIds,
    sendEmail: view.sendEmail,
  }
}

function audienceFields(values: AnnouncementValues) {
  return {
    audience: values.audience,
    ...(values.audience === 'GROUP' ? { group: values.group } : {}),
    ...(values.audience === 'SELECTED' ? { memberIds: values.memberIds } : {}),
  }
}

export function toCreateAnnouncementRequest(values: AnnouncementValues): CreateAnnouncementRequest {
  return {
    title: values.title,
    body: values.body,
    sendEmail: values.sendEmail,
    ...audienceFields(values),
  }
}

export function toUpdateAnnouncementRequest(values: AnnouncementValues): UpdateAnnouncementRequest {
  return {
    title: values.title,
    body: values.body,
    sendEmail: values.sendEmail,
    audience: values.audience,
    // The audience's other fields are cleared by the server when the audience changes.
    group: values.audience === 'GROUP' ? values.group : '',
    memberIds: values.audience === 'SELECTED' ? values.memberIds : [],
  }
}

/** A schedule the person typed (India date and time) as an instant, or why it cannot be used. */
export function parseSchedule(
  date: string,
  time: string,
  now: Date = new Date(),
): { ok: true; at: string } | { ok: false; message: string } {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return { ok: false, message: 'Choose a date' }
  if (!/^\d{2}:\d{2}$/.test(time)) return { ok: false, message: 'Choose a time' }
  const at = istToInstant(date, time)
  if (new Date(at) <= now) return { ok: false, message: 'Choose a time in the future' }
  return { ok: true, at }
}

export { instantToIst }

/** "12 of 40 members emailed, 3 have no address ...": what a send did, in words. */
export function describeDelivery(delivery: {
  recipientsTotal: number
  emailsQueued: number
  skippedNoEmail: number
  skippedNoConsent: number
  skippedQuota: number
}): string[] {
  const lines = [
    `${delivery.recipientsTotal.toLocaleString('en-IN')} in the audience, ${delivery.emailsQueued.toLocaleString('en-IN')} emailed`,
  ]
  if (delivery.skippedNoEmail > 0) lines.push(`${delivery.skippedNoEmail} have no email address`)
  if (delivery.skippedNoConsent > 0)
    lines.push(`${delivery.skippedNoConsent} have not agreed to email`)
  if (delivery.skippedQuota > 0)
    lines.push(`${delivery.skippedQuota} not emailed: the plan's email allowance ran out`)
  return lines
}
