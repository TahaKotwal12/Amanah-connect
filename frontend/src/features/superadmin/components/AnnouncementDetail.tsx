import { useState } from 'react'
import { formatDateTime } from '@/lib/dates'
import { notify } from '@/lib/notify'
import { Button } from '@/shared/components/ui/button'
import { ConfirmDialog } from '@/shared/components/ui/confirm-dialog'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { ErrorState } from '@/shared/components/ui/error-state'
import { Skeleton } from '@/shared/components/ui/skeleton'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { PlatformAnnouncementView } from '@/types'
import {
  useAnnouncementPreview,
  useSendAnnouncement,
  useSendAnnouncementTest,
} from '../hooks/useAnnouncements'

interface AnnouncementDetailProps {
  announcement: PlatformAnnouncementView | null
  onClose: () => void
  onEdit: (announcement: PlatformAnnouncementView) => void
}

/**
 * What an announcement says and who it would reach (counted by the server right now), with the
 * safe way to try it (a test to yourself) before the irreversible one (send now).
 */
export function AnnouncementDetail({ announcement, onClose, onEdit }: AnnouncementDetailProps) {
  const sent = announcement?.status === 'SENT'
  const preview = useAnnouncementPreview(announcement && !sent ? announcement.id : null)
  const test = useSendAnnouncementTest()
  const send = useSendAnnouncement()
  const [confirming, setConfirming] = useState(false)

  async function sendTest() {
    if (!announcement) return
    try {
      const result = await test.mutateAsync(announcement.id)
      notify.success(`A test was sent to ${result.sentTo}`)
    } catch (error) {
      notify.apiError(error)
    }
  }

  return (
    <Dialog open={announcement !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{announcement?.title ?? 'Announcement'}</DialogTitle>
          <DialogDescription>
            {announcement ? (
              <span className="flex flex-wrap items-center gap-2">
                <StatusBadge status={announcement.status} />
                {announcement.status === 'SCHEDULED' && announcement.scheduledAt
                  ? `Goes out ${formatDateTime(announcement.scheduledAt)}`
                  : null}
                {announcement.status === 'SENT' && announcement.sentAt
                  ? `Sent ${formatDateTime(announcement.sentAt)}`
                  : null}
              </span>
            ) : null}
          </DialogDescription>
        </DialogHeader>

        {announcement ? (
          <div className="grid gap-5">
            <section aria-label="Message">
              <p className="whitespace-pre-wrap rounded border border-brand-100 bg-brand-50 p-4 text-sm">
                {announcement.bodyText}
              </p>
            </section>

            {sent ? (
              <section aria-label="Delivery">
                <h3 className="mb-2 font-semibold text-brand-900">What happened</h3>
                <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
                  <div>
                    <dt className="text-muted">Administrators</dt>
                    <dd className="font-medium tabular-nums">
                      {announcement.delivery?.recipientsTotal ?? 0}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted">Emails queued</dt>
                    <dd className="font-medium tabular-nums">
                      {announcement.delivery?.emailsQueued ?? 0}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-muted">Read in the app</dt>
                    <dd className="font-medium tabular-nums">{announcement.readCount}</dd>
                  </div>
                </dl>
              </section>
            ) : (
              <section aria-label="Who it would reach">
                <h3 className="mb-2 font-semibold text-brand-900">Who it would reach</h3>
                {preview.isError ? (
                  <ErrorState
                    headingLevel={4}
                    error={preview.error}
                    onRetry={() => void preview.refetch()}
                    retrying={preview.isFetching}
                  />
                ) : preview.data ? (
                  <p className="text-sm">
                    {preview.data.adminRecipients.toLocaleString('en-IN')} administrators in{' '}
                    {preview.data.communities.toLocaleString('en-IN')} communities.{' '}
                    {preview.data.sendEmail ? 'Each gets an email. ' : ''}
                    {preview.data.banner ? 'A banner shows in the app.' : ''}
                  </p>
                ) : (
                  <Skeleton className="h-10" role="status" aria-label="Counting recipients" />
                )}
              </section>
            )}

            <div className="flex flex-wrap justify-end gap-2 border-t border-brand-100 pt-4">
              <Button variant="secondary" onClick={onClose}>
                Close
              </Button>
              {!sent ? (
                <>
                  <Button variant="secondary" onClick={() => onEdit(announcement)}>
                    Edit
                  </Button>
                  <Button
                    variant="secondary"
                    onClick={() => void sendTest()}
                    loading={test.isPending}
                  >
                    Send a test to me
                  </Button>
                  <Button onClick={() => setConfirming(true)}>Send now</Button>
                </>
              ) : null}
            </div>
          </div>
        ) : null}

        <ConfirmDialog
          open={confirming}
          onOpenChange={setConfirming}
          variant="danger"
          title="Send this announcement now?"
          description={
            preview.data
              ? `It reaches ${preview.data.adminRecipients.toLocaleString('en-IN')} administrators in ${preview.data.communities.toLocaleString('en-IN')} communities. This cannot be undone.`
              : 'This cannot be undone.'
          }
          confirmLabel="Send now"
          onConfirm={async () => {
            if (!announcement) return
            try {
              await send.mutateAsync(announcement.id)
              notify.success('Announcement sent')
              onClose()
            } catch (error) {
              notify.apiError(error)
              throw error
            }
          }}
        />
      </DialogContent>
    </Dialog>
  )
}
