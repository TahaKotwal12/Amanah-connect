import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query'
import type {
  ApproveRegistrationRequest,
  CreateInviteRequest,
  InviteByEmailRequest,
  PublicRegisterRequest,
  Uuid,
} from '@/types'
import {
  approveRegistration,
  createInvite,
  fetchInvites,
  fetchPublicInvite,
  fetchRegistrations,
  inviteByEmail,
  registerWithInvite,
  rejectRegistration,
  revokeInvite,
} from '../api/invites'
import {
  communityAdminKeys,
  type InviteListParams,
  type RegistrationListParams,
} from '../queryKeys'

export function useInvites(params: InviteListParams) {
  return useQuery({
    queryKey: communityAdminKeys.invites.list(params),
    queryFn: () => fetchInvites(params),
    placeholderData: keepPreviousData,
  })
}

export function useRegistrations(params: RegistrationListParams) {
  return useQuery({
    queryKey: communityAdminKeys.registrations.list(params),
    queryFn: () => fetchRegistrations(params),
    placeholderData: keepPreviousData,
  })
}

function refreshInvites(client: QueryClient) {
  return client.invalidateQueries({ queryKey: communityAdminKeys.invites.all })
}

/** Approving makes a member, so the member lists, counts and meter change with it. */
function refreshRegistrations(client: QueryClient) {
  return Promise.all([
    client.invalidateQueries({ queryKey: communityAdminKeys.registrations.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.members.all }),
    client.invalidateQueries({ queryKey: communityAdminKeys.plan() }),
    client.invalidateQueries({ queryKey: communityAdminKeys.dashboard() }),
  ])
}

export function useCreateInvite() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateInviteRequest) => createInvite(body),
    onSuccess: () => refreshInvites(client),
  })
}

export function useInviteByEmail() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: InviteByEmailRequest) => inviteByEmail(body),
    onSuccess: () => refreshInvites(client),
  })
}

export function useRevokeInvite() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (id: Uuid) => revokeInvite(id),
    onSuccess: () => refreshInvites(client),
  })
}

export function useApproveRegistration() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: Uuid; body?: ApproveRegistrationRequest }) =>
      approveRegistration(id, body),
    onSuccess: () => refreshRegistrations(client),
  })
}

export function useRejectRegistration() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: Uuid; reason: string }) => rejectRegistration(id, reason),
    onSuccess: () => refreshRegistrations(client),
  })
}

/** The public page: a visitor with no account, and no retry on a refused link. */
export function usePublicInvite(token: string) {
  return useQuery({
    queryKey: communityAdminKeys.publicInvite(token),
    queryFn: () => fetchPublicInvite(token),
    retry: false,
    staleTime: Infinity,
    meta: { silent: true },
  })
}

export function useRegisterWithInvite(token: string) {
  return useMutation({
    mutationFn: (body: PublicRegisterRequest) => registerWithInvite(token, body),
  })
}
