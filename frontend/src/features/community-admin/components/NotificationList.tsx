import { UserPlus } from 'lucide-react'
import { Link } from 'react-router'
import { formatRelative } from '@/lib/dates'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { cn } from '@/shared/utils/cn'
import { useMemberCounts } from '../hooks/useMembers'
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
} from '../hooks/usePlan'

export function NotificationList() {
  const list = useNotifications(true)
  const counts = useMemberCounts()
  const markRead = useMarkNotificationRead()
  const markAll = useMarkAllNotificationsRead()
  const pending = counts.data?.pendingRegistrations ?? 0
  const items = list.data?.items ?? []
  const anyUnread = items.some((item) => !item.read)

  return (
    <div className="grid gap-2">
      {pending > 0 ? (
        <Link
          to={`${paths.admin.invites}?tab=registrations`}
          className="flex min-h-11 items-center gap-2 rounded border border-brand-200 bg-brand-50 p-2 text-ink hover:border-brand-500"
        >
          <UserPlus aria-hidden="true" className="size-4 shrink-0 text-brand-600" />
          <span>
            {pending === 1
              ? '1 new registration is waiting'
              : `${pending} new registrations are waiting`}
          </span>
        </Link>
      ) : null}

      {list.isLoading ? <p>Loading</p> : null}
      {list.isError ? <p role="alert">The announcements could not be loaded.</p> : null}
      {!list.isLoading && !list.isError && items.length === 0 && pending === 0 ? (
        <p>You are all caught up.</p>
      ) : null}

      {items.length > 0 ? (
        <ul className="grid gap-1">
          {items.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => !item.read && markRead.mutate(item.id)}
                className={cn(
                  'grid min-h-11 w-full gap-0.5 rounded p-2 text-left hover:bg-brand-50',
                  !item.read && 'bg-brand-50',
                )}
              >
                <span className={cn('text-ink', !item.read && 'font-semibold')}>
                  {!item.read ? <span className="sr-only">Unread: </span> : null}
                  {item.title}
                </span>
                <span className="line-clamp-2 text-muted">{item.bodyText}</span>
                <span className="text-xs text-muted">{formatRelative(item.sentAt)}</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      {anyUnread ? (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => markAll.mutate()}
          loading={markAll.isPending}
        >
          Mark all as read
        </Button>
      ) : null}
    </div>
  )
}
