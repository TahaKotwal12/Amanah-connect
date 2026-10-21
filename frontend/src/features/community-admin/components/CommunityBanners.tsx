import { CircleAlert, Info, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { formatDate } from '@/lib/dates'
import { paths } from '@/routes/paths'
import { Button } from '@/shared/components/ui/button'
import { cn } from '@/shared/utils/cn'
import type { CommunityPlanView, NotificationView } from '@/types'
import { useCommunityPlan, useMarkNotificationRead, useNotificationSummary } from '../hooks/usePlan'

type Tone = 'danger' | 'warning' | 'info'

const TONE: Record<Tone, string> = {
  danger: 'border-danger/40 bg-danger-50 text-danger',
  warning: 'border-warning/40 bg-warning-50 text-ink',
  info: 'border-brand-200 bg-brand-50 text-brand-900',
}
const ICON = { danger: CircleAlert, warning: TriangleAlert, info: Info } as const

function Banner({
  tone,
  title,
  children,
  action,
}: {
  tone: Tone
  title: string
  children?: ReactNode
  action?: ReactNode
}) {
  const Icon = ICON[tone]
  return (
    <div
      role={tone === 'info' ? 'status' : 'alert'}
      className={cn('flex flex-wrap items-start gap-3 border-b px-4 py-3 md:px-8', TONE[tone])}
    >
      <Icon aria-hidden="true" className="mt-0.5 size-5 shrink-0" />
      <div className="min-w-0 flex-1">
        <p className="font-semibold">{title}</p>
        {children ? <p className="text-sm text-ink">{children}</p> : null}
      </div>
      {action}
    </div>
  )
}

function contactSupport(label = 'Contact support') {
  return (
    <Button asChild size="sm" variant="secondary">
      <Link to={paths.admin.support}>{label}</Link>
    </Button>
  )
}

/** The one banner the plan state calls for, or null when everything is fine. */
function planBanner(plan: CommunityPlanView): ReactNode {
  if (plan.communityStatus === 'SUSPENDED') {
    return (
      <Banner
        tone="danger"
        title="This community is suspended"
        action={contactSupport('Ask the platform team')}
      >
        You can still look at everything, but nothing can be added or changed until it is activated
        again.
      </Banner>
    )
  }
  if (plan.communityStatus === 'ARCHIVED') {
    return (
      <Banner tone="danger" title="This community is archived" action={contactSupport()}>
        It can only be read. Contact the platform team to bring it back.
      </Banner>
    )
  }
  const subscription = plan.subscription
  if (subscription?.status === 'EXPIRED') {
    return (
      <Banner
        tone="danger"
        title={`Your plan ended on ${formatDate(subscription.periodEnd)}`}
        action={contactSupport('Renew')}
      >
        Contact the platform team to renew so the community is not interrupted.
      </Banner>
    )
  }
  if (subscription?.status === 'EXPIRING') {
    const days = subscription.daysLeft
    return (
      <Banner
        tone="warning"
        title={
          days <= 0
            ? 'Your plan ends today'
            : `Your plan ends in ${days} ${days === 1 ? 'day' : 'days'}`
        }
        action={contactSupport('Renew')}
      >
        It runs until {formatDate(subscription.periodEnd)}. Renew before then so nothing stops.
      </Banner>
    )
  }
  return null
}

function PlatformBanner({ notification }: { notification: NotificationView }) {
  const markRead = useMarkNotificationRead()
  return (
    <Banner
      tone={notification.kind === 'MAINTENANCE' ? 'warning' : 'info'}
      title={notification.title}
      action={
        <Button
          size="sm"
          variant="ghost"
          loading={markRead.isPending}
          onClick={() => markRead.mutate(notification.id)}
        >
          Dismiss
        </Button>
      }
    >
      {notification.bodyText}
    </Banner>
  )
}

/**
 * Everything that sits between the header and the page: the plan or suspension notice, then the
 * platform's announcements that asked for a banner.
 */
export function CommunityBanners() {
  const plan = useCommunityPlan()
  const summary = useNotificationSummary()
  const planNotice = plan.data ? planBanner(plan.data) : null
  const platform = summary.data?.banners ?? []
  if (!planNotice && platform.length === 0) return null
  return (
    <div aria-label="Notices">
      {planNotice}
      {platform.map((notification) => (
        <PlatformBanner key={notification.id} notification={notification} />
      ))}
    </div>
  )
}
