import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Uuid } from '@/types'
import { fetchInvites, resendInvite, revokeInvite } from '../api/invites'
import { superadminKeys, type InviteListParams } from '../queryKeys'

export function useInvites(params: InviteListParams) {
  return useQuery({
    queryKey: superadminKeys.invites.list(params),
    queryFn: () => fetchInvites(params),
    placeholderData: keepPreviousData,
  })
}

/** An invitation also shows on the community's page (owner status), so those lists refresh too. */
function useRefreshInvites() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: superadminKeys.invites.all }),
      client.invalidateQueries({ queryKey: superadminKeys.communities.all }),
    ])
}

export function useResendInvite() {
  const refresh = useRefreshInvites()
  return useMutation({ mutationFn: (userId: Uuid) => resendInvite(userId), onSuccess: refresh })
}

export function useRevokeInvite() {
  const refresh = useRefreshInvites()
  return useMutation({ mutationFn: (userId: Uuid) => revokeInvite(userId), onSuccess: refresh })
}
