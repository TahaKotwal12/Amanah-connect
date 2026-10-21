import {
  Archive,
  Ban,
  CheckCircle2,
  CircleDashed,
  Clock,
  CornerUpLeft,
  FileText,
  Hourglass,
  Mail,
  MinusCircle,
  PauseCircle,
  PlayCircle,
  Send,
  TriangleAlert,
  XCircle,
  type LucideIcon,
} from 'lucide-react'
import { Badge, type BadgeProps } from './badge'

interface StatusStyle {
  label: string
  variant: NonNullable<BadgeProps['variant']>
  icon: LucideIcon
}

/**
 * Every status the backend sends, keyed by lower-case enum name. Colour AND an icon AND the word
 * are always shown, so colour-blind users and screen readers lose nothing.
 */
const STATUSES: Record<string, StatusStyle> = {
  // invoices and payments
  paid: { label: 'Paid', variant: 'success', icon: CheckCircle2 },
  partial: { label: 'Partly paid', variant: 'warning', icon: MinusCircle },
  pending: { label: 'Pending', variant: 'warning', icon: Clock },
  issued: { label: 'Issued', variant: 'brand', icon: FileText },
  overdue: { label: 'Overdue', variant: 'danger', icon: TriangleAlert },
  draft: { label: 'Draft', variant: 'neutral', icon: CircleDashed },
  cancelled: { label: 'Cancelled', variant: 'neutral', icon: Ban },
  reversed: { label: 'Reversed', variant: 'neutral', icon: CornerUpLeft },
  // people, communities, subscriptions
  active: { label: 'Active', variant: 'success', icon: CheckCircle2 },
  inactive: { label: 'Inactive', variant: 'neutral', icon: PauseCircle },
  suspended: { label: 'Suspended', variant: 'danger', icon: PauseCircle },
  archived: { label: 'Archived', variant: 'neutral', icon: Archive },
  invited: { label: 'Invited', variant: 'brand', icon: Mail },
  disabled: { label: 'Disabled', variant: 'neutral', icon: Ban },
  expired: { label: 'Expired', variant: 'danger', icon: Hourglass },
  expiring: { label: 'Expiring soon', variant: 'warning', icon: Hourglass },
  // registrations, complaints, support
  approved: { label: 'Approved', variant: 'success', icon: CheckCircle2 },
  rejected: { label: 'Rejected', variant: 'danger', icon: XCircle },
  open: { label: 'Open', variant: 'brand', icon: PlayCircle },
  in_progress: { label: 'In progress', variant: 'warning', icon: Clock },
  waiting: { label: 'Waiting', variant: 'warning', icon: Hourglass },
  resolved: { label: 'Resolved', variant: 'success', icon: CheckCircle2 },
  closed: { label: 'Closed', variant: 'neutral', icon: XCircle },
  // announcements, exports, email
  scheduled: { label: 'Scheduled', variant: 'brand', icon: Clock },
  sent: { label: 'Sent', variant: 'success', icon: Send },
  failed: { label: 'Failed', variant: 'danger', icon: XCircle },
  ready: { label: 'Ready', variant: 'success', icon: CheckCircle2 },
  running: { label: 'Running', variant: 'brand', icon: PlayCircle },
  // leads
  new: { label: 'New', variant: 'brand', icon: CircleDashed },
  contacted: { label: 'Contacted', variant: 'brand', icon: Mail },
  demo_scheduled: { label: 'Demo scheduled', variant: 'gold', icon: Clock },
  converted: { label: 'Converted', variant: 'success', icon: CheckCircle2 },
  lost: { label: 'Lost', variant: 'neutral', icon: XCircle },
}

function humanize(status: string): string {
  const words = status.toLowerCase().replace(/_/g, ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export interface StatusBadgeProps extends Omit<BadgeProps, 'variant' | 'children'> {
  /** The backend enum name (INVOICE: "OVERDUE") or any lower-case key. Unknown values render neutral. */
  status: string
  /** Override the visible text, for example "Overdue by 3 days". */
  label?: string
}

export function StatusBadge({ status, label, ...props }: StatusBadgeProps) {
  const style = STATUSES[status.toLowerCase()]
  const Icon = style?.icon ?? CircleDashed
  return (
    <Badge variant={style?.variant ?? 'neutral'} {...props}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      {label ?? style?.label ?? humanize(status)}
    </Badge>
  )
}

export const KNOWN_STATUSES: readonly string[] = Object.keys(STATUSES)
