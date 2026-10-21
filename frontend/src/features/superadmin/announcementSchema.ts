import { z } from 'zod'
import { todayInIst } from '@/lib/dates'
import type {
  AnnouncementKind,
  CreatePlatformAnnouncementRequest,
  PlatformAnnouncementView,
  UpdatePlatformAnnouncementRequest,
} from '@/types'

export { endOfDayInstant, instantToIst, istToInstant } from '@/lib/ist'
import { endOfDayInstant, instantToIst, istToInstant } from '@/lib/ist'

export const KIND_OPTIONS: ReadonlyArray<{ value: AnnouncementKind; label: string }> = [
  { value: 'ANNOUNCEMENT', label: 'Announcement' },
  { value: 'OFFER', label: 'Offer' },
  { value: 'MAINTENANCE', label: 'Maintenance notice' },
]

export const announcementSchema = z
  .object({
    title: z.string().trim().min(1, 'Enter a title').max(200, 'Use 200 characters or fewer'),
    body: z
      .string()
      .trim()
      .min(1, 'Write the message')
      .max(20000, 'Use 20,000 characters or fewer'),
    kind: z.enum(['ANNOUNCEMENT', 'OFFER', 'MAINTENANCE']),
    audience: z.enum(['all', 'selected']),
    communityIds: z.array(z.string()),
    sendEmail: z.boolean(),
    banner: z.boolean(),
    expiresOn: z.union([z.literal(''), z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a date')]),
    when: z.enum(['draft', 'now', 'later']),
    scheduleDate: z.string(),
    scheduleTime: z.string(),
  })
  .superRefine((value, context) => {
    if (!value.sendEmail && !value.banner) {
      context.addIssue({
        code: 'custom',
        path: ['sendEmail'],
        message: 'Choose how it reaches people: email, a banner in the app, or both',
      })
    }
    if (value.audience === 'selected' && value.communityIds.length === 0) {
      context.addIssue({
        code: 'custom',
        path: ['communityIds'],
        message: 'Choose at least one community, or send to all',
      })
    }
    if (value.expiresOn !== '' && value.expiresOn <= todayInIst()) {
      context.addIssue({
        code: 'custom',
        path: ['expiresOn'],
        message: 'Choose a day after today',
      })
    }
    if (value.when === 'later') {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(value.scheduleDate)) {
        context.addIssue({ code: 'custom', path: ['scheduleDate'], message: 'Choose a day' })
      } else if (!/^\d{2}:\d{2}$/.test(value.scheduleTime)) {
        context.addIssue({ code: 'custom', path: ['scheduleTime'], message: 'Choose a time' })
      } else if (new Date(istToInstant(value.scheduleDate, value.scheduleTime)) <= new Date()) {
        context.addIssue({
          code: 'custom',
          path: ['scheduleDate'],
          message: 'Choose a time in the future',
        })
      }
    }
  })

export type AnnouncementInput = z.input<typeof announcementSchema>
export type AnnouncementValues = z.output<typeof announcementSchema>

export const EMPTY_ANNOUNCEMENT: AnnouncementInput = {
  title: '',
  body: '',
  kind: 'ANNOUNCEMENT',
  audience: 'all',
  communityIds: [],
  sendEmail: true,
  banner: false,
  expiresOn: '',
  when: 'draft',
  scheduleDate: '',
  scheduleTime: '09:00',
}

export function announcementToForm(view: PlatformAnnouncementView): AnnouncementInput {
  const scheduled = view.scheduledAt ? instantToIst(view.scheduledAt) : null
  return {
    title: view.title,
    body: view.bodyText,
    kind: view.kind,
    audience: view.communityIds && view.communityIds.length > 0 ? 'selected' : 'all',
    communityIds: view.communityIds ?? [],
    sendEmail: view.sendEmail,
    banner: view.banner,
    expiresOn: view.expiresAt ? instantToIst(view.expiresAt).date : '',
    when: scheduled ? 'later' : 'draft',
    scheduleDate: scheduled?.date ?? '',
    scheduleTime: scheduled?.time ?? '09:00',
  }
}

export function toCreateRequest(values: AnnouncementValues): CreatePlatformAnnouncementRequest {
  return {
    title: values.title,
    body: values.body,
    kind: values.kind,
    sendEmail: values.sendEmail,
    banner: values.banner,
    ...(values.audience === 'selected' ? { communityIds: values.communityIds } : {}),
    ...(values.expiresOn ? { expiresAt: endOfDayInstant(values.expiresOn) } : {}),
    ...(values.when === 'later'
      ? { scheduledAt: istToInstant(values.scheduleDate, values.scheduleTime) }
      : {}),
  }
}

/** Every editable field is sent, so what is on screen is exactly what is saved. */
export function toUpdateRequest(values: AnnouncementValues): UpdatePlatformAnnouncementRequest {
  return {
    title: values.title,
    body: values.body,
    kind: values.kind,
    sendEmail: values.sendEmail,
    banner: values.banner,
    ...(values.audience === 'selected'
      ? { communityIds: values.communityIds }
      : { allCommunities: true }),
    ...(values.expiresOn
      ? { expiresAt: endOfDayInstant(values.expiresOn) }
      : { clearExpiry: true }),
    ...(values.when === 'later'
      ? { scheduledAt: istToInstant(values.scheduleDate, values.scheduleTime) }
      : { unschedule: true }),
  }
}
