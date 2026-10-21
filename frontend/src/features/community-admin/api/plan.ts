import { api } from '@/lib/axios'
import type {
  CommunityPlanView,
  NotificationSummary,
  NotificationView,
  PageResponse,
} from '@/types'

export async function fetchCommunityPlan(): Promise<CommunityPlanView> {
  const { data } = await api.get<CommunityPlanView>('/community/plan')
  return data
}

export async function fetchNotificationSummary(): Promise<NotificationSummary> {
  const { data } = await api.get<NotificationSummary>('/community/notifications/summary')
  return data
}

export async function fetchNotifications(): Promise<PageResponse<NotificationView>> {
  const { data } = await api.get<PageResponse<NotificationView>>('/community/notifications', {
    params: { size: 10 },
  })
  return data
}

export async function markNotificationRead(id: string): Promise<void> {
  await api.post(`/community/notifications/${id}/read`)
}

export async function markAllNotificationsRead(): Promise<void> {
  await api.post('/community/notifications/read-all')
}
