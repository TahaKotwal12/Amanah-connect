import { z } from 'zod'
import type { ComplaintStatus, ComplaintView, CreateComplaintRequest, Priority } from '@/types'

export const COMPLAINT_STATUSES: ReadonlyArray<{ value: ComplaintStatus; label: string }> = [
  { value: 'OPEN', label: 'Open' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'RESOLVED', label: 'Resolved' },
  { value: 'CLOSED', label: 'Closed' },
]

export const PRIORITIES: ReadonlyArray<{ value: Priority; label: string }> = [
  { value: 'LOW', label: 'Low' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'HIGH', label: 'High' },
  { value: 'URGENT', label: 'Urgent' },
]

export const statusLabel = (status: ComplaintStatus): string =>
  COMPLAINT_STATUSES.find((s) => s.value === status)?.label ?? status

export const isActive = (complaint: Pick<ComplaintView, 'status'>): boolean =>
  complaint.status === 'OPEN' || complaint.status === 'IN_PROGRESS'

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/** "Day 3 of 7", "2 days late", "Resolved in 4 days": where a complaint stands against its target. */
export function describeSla(
  complaint: Pick<ComplaintView, 'status' | 'ageDays' | 'slaTargetDays' | 'slaBreached'>,
): string {
  if (complaint.status === 'RESOLVED' || complaint.status === 'CLOSED') {
    return complaint.ageDays === 0
      ? 'Finished the same day'
      : `Finished in ${plural(complaint.ageDays, 'day')}`
  }
  if (complaint.slaBreached) {
    const late = Math.max(1, complaint.ageDays - complaint.slaTargetDays)
    return `${plural(late, 'day')} past the ${complaint.slaTargetDays}-day target`
  }
  return `${plural(complaint.ageDays, 'day')} old, target ${complaint.slaTargetDays}`
}

export const newComplaintSchema = z.object({
  memberId: z.string(),
  subject: z.string().trim().min(1, 'Enter a subject').max(200, 'Use 200 characters or fewer'),
  description: z
    .string()
    .trim()
    .min(1, 'Describe the problem')
    .max(5000, 'Use 5,000 characters or fewer'),
  priority: z.enum(['LOW', 'MEDIUM', 'HIGH', 'URGENT']),
  category: z.string().trim().max(60, 'Use 60 characters or fewer'),
  assignedTo: z.string(),
})
export type NewComplaintInput = z.input<typeof newComplaintSchema>
export type NewComplaintValues = z.output<typeof newComplaintSchema>

export const emptyComplaint = (memberId = ''): NewComplaintInput => ({
  memberId,
  subject: '',
  description: '',
  priority: 'MEDIUM',
  category: '',
  assignedTo: '',
})

export function toCreateComplaintRequest(values: NewComplaintValues): CreateComplaintRequest {
  return {
    subject: values.subject,
    description: values.description,
    priority: values.priority,
    ...(values.memberId ? { memberId: values.memberId } : {}),
    ...(values.category ? { category: values.category } : {}),
    ...(values.assignedTo ? { assignedTo: values.assignedTo } : {}),
  }
}

export const commentSchema = z.object({
  body: z
    .string()
    .trim()
    .min(1, 'Write something first')
    .max(5000, 'Use 5,000 characters or fewer'),
})

/** What the server did about an email we asked for, in words. */
export function describeEmailOutcome(outcome: string | null): string | null {
  switch (outcome) {
    case 'QUEUED':
      return 'The member is being emailed.'
    case 'NO_ADDRESS':
      return 'Saved, but the member has no email address, so nothing was sent.'
    case 'NO_CONSENT':
      return 'Saved, but the member has not agreed to email, so nothing was sent.'
    case 'QUOTA':
      return 'Saved, but your plan’s email allowance has run out, so nothing was sent.'
    default:
      return null
  }
}
