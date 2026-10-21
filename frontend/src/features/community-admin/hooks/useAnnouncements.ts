import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { CreateAnnouncementRequest, UpdateAnnouncementRequest, Uuid } from '@/types'
import {
  createAnnouncement,
  fetchAnnouncement,
  fetchAnnouncements,
  previewAnnouncement,
  scheduleAnnouncement,
  sendAnnouncement,
  sendAnnouncementTest,
  unscheduleAnnouncement,
  updateAnnouncement,
} from '../api/announcements'
import { communityAdminKeys, type AnnouncementListParams } from '../queryKeys'

export function useAnnouncements(params: AnnouncementListParams) {
  return useQuery({
    queryKey: communityAdminKeys.announcements.list(params),
    queryFn: () => fetchAnnouncements(params),
    placeholderData: keepPreviousData,
  })
}

export function useAnnouncement(id: Uuid | null) {
  return useQuery({
    queryKey: communityAdminKeys.announcements.detail(id ?? ''),
    queryFn: () => fetchAnnouncement(id as Uuid),
    enabled: id !== null,
  })
}

/** Who a send would reach and what the quota allows. Read-only; never queues anything. */
export function useAnnouncementPreview(id: Uuid | null) {
  return useQuery({
    queryKey: communityAdminKeys.announcements.preview(id ?? ''),
    queryFn: () => previewAnnouncement(id as Uuid),
    enabled: id !== null,
    staleTime: 0,
    gcTime: 0,
  })
}

/** Sending uses the monthly email allowance, so the plan meter and the bell are refreshed too. */
function useRefreshAnnouncements() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: communityAdminKeys.announcements.all }),
      client.invalidateQueries({ queryKey: communityAdminKeys.plan() }),
    ])
}

export function useCreateAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({
    mutationFn: (body: CreateAnnouncementRequest) => createAnnouncement(body),
    onSuccess: refresh,
  })
}

export function useUpdateAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body: UpdateAnnouncementRequest }) =>
      updateAnnouncement(id, body),
    onSuccess: refresh,
  })
}

export function useScheduleAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({
    mutationFn: ({ id, scheduledAt }: { id: Uuid; scheduledAt: string }) =>
      scheduleAnnouncement(id, { scheduledAt }),
    onSuccess: refresh,
  })
}

export function useUnscheduleAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({ mutationFn: (id: Uuid) => unscheduleAnnouncement(id), onSuccess: refresh })
}

export function useSendAnnouncement() {
  const refresh = useRefreshAnnouncements()
  return useMutation({ mutationFn: (id: Uuid) => sendAnnouncement(id), onSuccess: refresh })
}

export function useSendAnnouncementTest() {
  const refresh = useRefreshAnnouncements()
  return useMutation({ mutationFn: (id: Uuid) => sendAnnouncementTest(id), onSuccess: refresh })
}
