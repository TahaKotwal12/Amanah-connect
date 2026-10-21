import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { CommunityPlanView } from '@/types'
import {
  fetchCommunityPlan,
  fetchNotificationSummary,
  fetchNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '../api/plan'
import { communityAdminKeys } from '../queryKeys'

const POLL_MS = 60_000

export function useCommunityPlan() {
  return useQuery({
    queryKey: communityAdminKeys.plan(),
    queryFn: fetchCommunityPlan,
    staleTime: POLL_MS,
    // A suspension or a payment recorded by the platform shows up without a reload.
    refetchInterval: POLL_MS * 5,
    refetchIntervalInBackground: false,
    meta: { silent: true },
  })
}

export interface WriteAccess {
  /** False when the community is suspended or archived: every change is refused by the server. */
  allowed: boolean
  /** Why, in words, for the explanation next to a disabled button. */
  reason: string | null
}

/** While the plan has not loaded, writes are allowed: the server is the one that refuses them. */
export function writeAccessOf(plan: CommunityPlanView | undefined): WriteAccess {
  if (plan?.communityStatus === 'SUSPENDED') {
    return {
      allowed: false,
      reason:
        'This community is suspended, so nothing can be added or changed until it is activated.',
    }
  }
  if (plan?.communityStatus === 'ARCHIVED') {
    return { allowed: false, reason: 'This community is archived, so it can only be read.' }
  }
  return { allowed: true, reason: null }
}

export function useWriteAccess(): WriteAccess {
  const plan = useCommunityPlan()
  return writeAccessOf(plan.data)
}

/** The platform's announcements and the unread count for the bell. Cheap, so it is polled. */
export function useNotificationSummary() {
  return useQuery({
    queryKey: communityAdminKeys.notificationSummary(),
    queryFn: fetchNotificationSummary,
    refetchInterval: POLL_MS,
    refetchIntervalInBackground: false,
    meta: { silent: true },
  })
}

export function useNotifications(enabled: boolean) {
  return useQuery({
    queryKey: communityAdminKeys.notifications(),
    queryFn: fetchNotifications,
    enabled,
  })
}

function useRefreshNotifications() {
  const client = useQueryClient()
  return () => client.invalidateQueries({ queryKey: communityAdminKeys.notifications() })
}

export function useMarkNotificationRead() {
  const refresh = useRefreshNotifications()
  return useMutation({
    mutationFn: (id: string) => markNotificationRead(id),
    onSuccess: refresh,
  })
}

export function useMarkAllNotificationsRead() {
  const refresh = useRefreshNotifications()
  return useMutation({ mutationFn: markAllNotificationsRead, onSuccess: refresh })
}
