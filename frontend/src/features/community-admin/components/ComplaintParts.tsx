import { Clock, TriangleAlert } from 'lucide-react'
import { Badge } from '@/shared/components/ui/badge'
import type { ComplaintView, Priority } from '@/types'
import { PRIORITIES, describeSla, isActive } from '../complaintSchemas'

const VARIANT = { URGENT: 'danger', HIGH: 'warning', MEDIUM: 'brand', LOW: 'neutral' } as const

export function PriorityBadge({ priority }: { priority: Priority }) {
  return (
    <Badge variant={VARIANT[priority]}>
      {PRIORITIES.find((p) => p.value === priority)?.label ?? priority}
    </Badge>
  )
}

/** Age against the target, in words and an icon (never colour alone). */
export function SlaIndicator({ complaint }: { complaint: ComplaintView }) {
  const breached = complaint.slaBreached
  const Icon = breached ? TriangleAlert : Clock
  return (
    <span
      className={
        breached
          ? 'inline-flex items-center gap-1.5 text-sm font-medium text-danger'
          : isActive(complaint)
            ? 'inline-flex items-center gap-1.5 text-sm'
            : 'inline-flex items-center gap-1.5 text-sm text-muted'
      }
    >
      <Icon aria-hidden="true" className="size-4 shrink-0" />
      {describeSla(complaint)}
    </span>
  )
}
