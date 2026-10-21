import { formatDateTime } from '@/lib/dates'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/shared/components/ui/dialog'
import { StatusBadge } from '@/shared/components/ui/status-badge'
import type { AnnouncementView } from '@/types'
import { AUDIENCES, describeDelivery } from '../announcementSchemas'
import { SafeHtmlFrame } from './SafeHtmlFrame'

/** A past or planned announcement: its message as members saw it, and what the send did. */
export function AnnouncementDetailDialog({
  announcement,
  onClose,
}: {
  announcement: AnnouncementView | null
  onClose: () => void
}) {
  return (
    <Dialog open={announcement !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[92vh] max-w-2xl overflow-y-auto">
        {announcement ? (
          <>
            <DialogHeader>
              <DialogTitle>{announcement.title}</DialogTitle>
              <DialogDescription>
                {AUDIENCES.find((a) => a.value === announcement.audience)?.label ?? 'Members'}
                {announcement.group ? `: ${announcement.group}` : ''}
              </DialogDescription>
            </DialogHeader>
            <div className="flex flex-wrap items-center gap-3 text-sm">
              <StatusBadge status={announcement.status} />
              {announcement.sentAt ? <span>Sent {formatDateTime(announcement.sentAt)}</span> : null}
              {announcement.status === 'SCHEDULED' && announcement.scheduledAt ? (
                <span>Goes out {formatDateTime(announcement.scheduledAt)}</span>
              ) : null}
            </div>
            <SafeHtmlFrame title="The announcement" html={announcement.bodyHtml} className="h-72" />
            {announcement.delivery ? (
              <ul aria-label="Delivery" className="grid gap-1 text-sm">
                {describeDelivery(announcement.delivery).map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            ) : announcement.sendEmail ? (
              <p className="text-sm text-muted">Delivery numbers appear once it is sent.</p>
            ) : (
              <p className="text-sm text-muted">Email was off, so nobody was emailed.</p>
            )}
          </>
        ) : null}
      </DialogContent>
    </Dialog>
  )
}
