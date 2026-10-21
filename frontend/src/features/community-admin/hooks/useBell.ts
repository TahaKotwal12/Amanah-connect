import { useMemberCounts } from './useMembers'
import { useNotificationSummary } from './usePlan'

/** What the bell counts: new registrations waiting, plus the platform's unread announcements. */
export function useBellCount(): number {
  const summary = useNotificationSummary()
  const counts = useMemberCounts()
  return (summary.data?.unread ?? 0) + (counts.data?.pendingRegistrations ?? 0)
}
