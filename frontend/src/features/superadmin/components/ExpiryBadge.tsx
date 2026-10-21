import { StatusBadge } from '@/shared/components/ui/status-badge'

interface ExpiryBadgeProps {
  /** ACTIVE, EXPIRING, EXPIRED or CANCELLED: the backend's display status. */
  status: string
  /** Days until the period ends; negative once it has ended. */
  daysLeft: number
}

function dayWord(days: number): string {
  return days === 1 ? '1 day' : `${days} days`
}

/** Status plus the number that matters: how long is left, or how long ago it ended. */
export function ExpiryBadge({ status, daysLeft }: ExpiryBadgeProps) {
  if (status === 'EXPIRING') {
    return (
      <StatusBadge
        status="expiring"
        label={daysLeft <= 0 ? 'Ends today' : `Ends in ${dayWord(daysLeft)}`}
      />
    )
  }
  if (status === 'EXPIRED') {
    return <StatusBadge status="expired" label={`Ended ${dayWord(Math.abs(daysLeft))} ago`} />
  }
  return <StatusBadge status={status} />
}
