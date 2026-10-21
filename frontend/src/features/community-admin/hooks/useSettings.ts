import { useQuery } from '@tanstack/react-query'
import { fetchSettings } from '../api/settings'
import { communityAdminKeys } from '../queryKeys'

/** Profile, logo, group label and UPI details. Changes rarely, so it is kept for a while. */
export function useSettings() {
  return useQuery({
    queryKey: communityAdminKeys.settings(),
    queryFn: fetchSettings,
    staleTime: 5 * 60_000,
    meta: { silent: true },
  })
}
