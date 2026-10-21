import { notify } from '@/lib/notify'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import type { Uuid } from '@/types'
import { useChangeCommunityStatus, useResetAdminPassword } from '../hooks/useCommunities'
import { ReasonDialog } from '@/shared/components/ui/reason-dialog'

export type CommunityActionKind = 'suspend' | 'activate' | 'archive' | 'reset'

export interface CommunityActionTarget {
  kind: CommunityActionKind
  id: Uuid
  name: string
  /** Current status, to word "activate" correctly. */
  status?: string
}

interface CommunityActionDialogsProps {
  target: CommunityActionTarget | null
  onClose: () => void
}

/**
 * The confirmation for each destructive community action, shared by the list and the detail page.
 * Suspend and archive need a written reason (kept in the audit log); activate takes an optional
 * one; resetting the admin's password asks first because it signs them out everywhere.
 */
export function CommunityActionDialogs({ target, onClose }: CommunityActionDialogsProps) {
  const change = useChangeCommunityStatus()
  const reset = useResetAdminPassword()
  const open = (kind: CommunityActionKind) => target?.kind === kind
  const name = target?.name ?? 'this community'
  const restoring = target?.status === 'ARCHIVED'
  const close = (next: boolean) => {
    if (!next) onClose()
  }

  return (
    <>
      <ReasonDialog
        open={open('suspend')}
        onOpenChange={close}
        title={`Suspend ${name}?`}
        description="The community becomes read-only: nobody can add or change anything until it is activated again. The owner is emailed."
        confirmLabel="Suspend"
        onSubmit={async (reason) => {
          if (!target) return
          await change.mutateAsync({ id: target.id, action: 'suspend', reason })
          notify.success(`${target.name} is suspended`)
        }}
      />
      <ReasonDialog
        open={open('archive')}
        onOpenChange={close}
        title={`Archive ${name}?`}
        description="Nothing is deleted, but the community is hidden from the list and becomes read-only. It can be restored by activating it."
        confirmLabel="Archive"
        onSubmit={async (reason) => {
          if (!target) return
          await change.mutateAsync({ id: target.id, action: 'archive', reason })
          notify.success(`${target.name} is archived`)
        }}
      />
      <ReasonDialog
        open={open('activate')}
        onOpenChange={close}
        title={`${restoring ? 'Restore' : 'Activate'} ${name}?`}
        description="The community can be used normally again. The owner is emailed."
        confirmLabel={restoring ? 'Restore' : 'Activate'}
        variant="default"
        required={false}
        onSubmit={async (reason) => {
          if (!target) return
          await change.mutateAsync({
            id: target.id,
            action: 'activate',
            ...(reason ? { reason } : {}),
          })
          notify.success(`${target.name} is active`)
        }}
      />
      <ConfirmDialog
        open={open('reset')}
        onOpenChange={close}
        title={`Reset the admin's password for ${name}?`}
        description="The administrator is signed out everywhere and emailed a link to choose a new password. If they never accepted their invitation, it is sent again instead."
        confirmLabel="Send email"
        variant="danger"
        onConfirm={async () => {
          if (!target) return
          try {
            const outcome = await reset.mutateAsync(target.id)
            notify.success(
              outcome.action === 'INVITATION_RESENT'
                ? 'The invitation was sent again'
                : 'A password reset email is on its way',
            )
          } catch (error) {
            notify.apiError(error)
            throw error
          }
        }}
      />
    </>
  )
}
